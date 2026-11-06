package com.vortex.workflow;

import com.fasterxml.jackson.databind.*;
import com.vortex.VortexApplication;
import com.vortex.bagstate.api.BagQuery;
import com.vortex.bagstate.domain.BagState;
import com.vortex.messaging.envelope.*;
import com.vortex.messaging.kafka.*;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Separate JVM fixture. Never loaded by production or ordinary test component scanning. */
public class Phase6RestartWorker {
    @TestConfiguration
    public static class Time {
        @Bean @Primary java.time.Clock phase6Clock() {
            return java.time.Clock.fixed(Instant.parse("2030-01-15T10:00:00Z"), ZoneOffset.UTC);
        }
    }
    public static void main(String[] args) throws Exception {
        boolean prepare = args[0].equals("prepare");
        String stage = args[1], prefix = args[3];
        Path directory = Path.of(args[2]);
        if (!System.getenv().getOrDefault("DATABASE_URL", "").contains("/vortex_test_"))
            throw new IllegalStateException("A disposable test database is required");
        if (!prefix.startsWith("vortex.phase6-test.")) throw new IllegalArgumentException("Test topics required");
        List<String> settings = new ArrayList<>(List.of("--server.port=0", "--spring.main.banner-mode=off",
                "--logging.level.root=ERROR", "--springdoc.api-docs.enabled=false", "--springdoc.swagger-ui.enabled=false",
                "--spring.kafka.listener.auto-startup=" + !prepare, "--vortex.outbox.enabled=" + !prepare,
                "--vortex.movement.sweeper-enabled=false", "--vortex.outbox.poll-interval-ms=20"));
        for (String name : List.of("workflow-events", "equipment-commands", "simulator-reports", "quarantine"))
            settings.add("--vortex.kafka.topics." + name + "=" + prefix + "." + name);
        for (String name : List.of("workflow", "ui", "simulator-reports"))
            settings.add("--vortex.kafka.groups." + name + "=" + prefix + "-" + name);
        try (var app = new SpringApplicationBuilder(VortexApplication.class, Time.class).run(settings.toArray(String[]::new))) {
            JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
            assertTrue(jdbc.queryForObject("select current_database()", String.class).startsWith("vortex_test_"));
            ObjectMapper mapper = app.getBean(ObjectMapper.class);
            var codec = app.getBean(EventEnvelopeJsonCodec.class);
            var bags = app.getBean(BagQuery.class);
            int port = ((ServletWebServerApplicationContext) app).getWebServer().getPort();
            String base = "http://127.0.0.1:" + port;
            if (prepare) {
                String tag = "P6-" + UUID.randomUUID(), key = UUID.randomUUID().toString();
                JsonNode accepted = post(mapper, base + "/api/bags/check-in", key,
                        mapper.createObjectNode().put("bagTag", tag).put("flightId", "FL-204-2030-01-15"), 202);
                UUID id = UUID.fromString(accepted.path("bagId").asText());
                var workflow = app.getBean(WorkflowKafkaListener.class);
                for (String event : List.of("BagCheckedIn", "FlightStatusVerified", "RoutePlanned")) {
                    if (stage.equals("CHECKED_IN") || (event.equals("FlightStatusVerified") && stage.equals("FLIGHT_VERIFIED"))
                            || (event.equals("RoutePlanned") && stage.equals("ROUTE_PLANNED"))) break;
                    byte[] bytes = jdbc.queryForObject("SELECT envelope::text FROM vortex.outbox WHERE aggregate_id = ? AND event_type = ?",
                            String.class, id.toString(), event).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    workflow.receive(new org.apache.kafka.clients.consumer.ConsumerRecord<>(prefix + ".workflow-events", 0, 0, id.toString(), bytes));
                }
                if (Set.of("IN_TRANSIT", "WAITING_FOR_OBSERVATION", "INTERMEDIATE_CONFIRMED", "UNCERTAIN_COMMAND").contains(stage)) {
                    byte[] command = jdbc.queryForObject("SELECT envelope::text FROM vortex.outbox WHERE event_id = ?", String.class,
                            bags.find(id).orElseThrow().activeCommandId()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    Files.write(directory.resolve("command.json"), command);
                    runPython(directory, stage.equals("UNCERTAIN_COMMAND") ? "intent" : "command");
                    if (!stage.equals("UNCERTAIN_COMMAND")) {
                        JsonNode reports = mapper.readTree(directory.resolve("reports.json").toFile());
                        int count = stage.equals("IN_TRANSIT") ? 2 : stage.equals("WAITING_FOR_OBSERVATION") ? 3 : 4;
                        for (int i = 0; i < count; i++) app.getBean(SimulatorReportKafkaListener.class).receive(
                                new org.apache.kafka.clients.consumer.ConsumerRecord<>(prefix + ".simulator-reports", 0, i, id.toString(), mapper.writeValueAsBytes(reports.get(i))));
                    }
                }
                var checkpoint = mapper.createObjectNode().put("bagId", id.toString()).put("tag", tag).put("key", key)
                        .put("state", bags.find(id).orElseThrow().state().name())
                        .put("node", bags.find(id).orElseThrow().confirmedNodeId());
                checkpoint.set("historyIds", mapper.valueToTree(bags.history(id).stream().map(h -> h.historyId().toString()).toList()));
                mapper.writeValue(directory.resolve("checkpoint.json").toFile(), checkpoint);
                // Abrupt backend death after the checkpoint's business transactions committed.
                Runtime.getRuntime().halt(0);
            } else {
                JsonNode checkpoint = mapper.readTree(directory.resolve("checkpoint.json").toFile());
                UUID id = UUID.fromString(checkpoint.path("bagId").asText());
                Process simulator = python(directory, "serve").start();
                try {
                    if (stage.equals("UNCERTAIN_COMMAND")) {
                        await().atMost(Duration.ofSeconds(40)).until(() -> bags.find(id).orElseThrow().state() == BagState.MOVEMENT_FAILED);
                        var failed = bags.find(id).orElseThrow();
                        assertEquals("SIMULATOR_EXECUTION_UNCERTAIN", failed.failureCode());
                        assertEquals("checkin-main", failed.confirmedNodeId());
                        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM vortex.equipment_observations WHERE bag_id = ?", Integer.class, id));
                        String key = UUID.randomUUID().toString();
                        JsonNode response = post(mapper, base + "/api/bags/" + id + "/retry", key, mapper.createObjectNode(), 202);
                        await().atMost(Duration.ofSeconds(40)).until(() -> bags.find(id).orElseThrow().state() == BagState.AT_MAKEUP);
                        assertEquals(response, post(mapper, base + "/api/bags/" + id + "/retry", key, mapper.createObjectNode(), 202));
                        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM vortex.bag_history WHERE bag_id = ? AND record_type = 'RETRY_REQUEST'", Integer.class, id));
                    } else {
                        await().atMost(Duration.ofSeconds(40)).until(() -> bags.find(id).orElseThrow().state() == BagState.AT_MAKEUP);
                    }
                    assertTrue(simulator.isAlive(), "Simulator must remain healthy");
                    var bag = bags.find(id).orElseThrow();
                    assertEquals("makeup-lhr", bag.confirmedNodeId());
                    assertEquals("COMPLETE", app.getBean(com.vortex.routing.api.RouteQuery.class).latest(id).orElseThrow().status());
                    assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM vortex.equipment_observations WHERE bag_id = ? AND decision = 'ACCEPTED'", Integer.class, id));
                    assertEquals(stage.equals("UNCERTAIN_COMMAND") ? 3 : 2, app.getBean(com.vortex.movement.api.MovementQuery.class).commands(id).size());
                    Set<String> history = new HashSet<>(bags.history(id).stream().map(h -> h.historyId().toString()).toList());
                    for (JsonNode prior : checkpoint.path("historyIds")) assertTrue(history.contains(prior.asText()), "Original history must survive restart");
                    // Replay final scan over actual Kafka, wait for its committed offset, then assert no extra effect.
                    var row = jdbc.queryForMap("SELECT * FROM vortex.equipment_observations WHERE bag_id = ? AND node_id = 'makeup-lhr'", id);
                    var replay = new EventEnvelope((UUID) row.get("event_id"), EventType.SCANNER_OBSERVATION, 1, Instant.parse("2030-01-15T10:00:00Z"),
                            "python-equipment-simulator", AggregateType.BAG, id.toString(), bag.correlationId(), (UUID) row.get("command_id"), mapper.readTree(row.get("validated_payload").toString()));
                    @SuppressWarnings("unchecked") var kafka = (KafkaTemplate<String, byte[]>) app.getBean(KafkaTemplate.class);
                    var metadata = kafka.send(prefix + ".simulator-reports", id.toString(), codec.serialize(replay)).get(10, TimeUnit.SECONDS).getRecordMetadata();
                    try (Admin admin = Admin.create(Map.of("bootstrap.servers", System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")))) {
                        await().atMost(Duration.ofSeconds(15)).until(() -> {
                            var offset = admin.listConsumerGroupOffsets(prefix + "-simulator-reports").partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS)
                                    .get(new TopicPartition(prefix + ".simulator-reports", metadata.partition()));
                            return offset != null && offset.offset() > metadata.offset();
                        });
                    }
                    assertEquals(bag.stateVersion(), bags.find(id).orElseThrow().stateVersion());
                    assertEquals(history.size(), bags.history(id).size());
                    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM vortex.bag_history WHERE bag_id = ? AND current_state = 'AT_MAKEUP'", Integer.class, id));
                    assertEquals(id.toString(), post(mapper, base + "/api/bags/check-in", checkpoint.path("key").asText(),
                            mapper.createObjectNode().put("bagTag", checkpoint.path("tag").asText()).put("flightId", "FL-204-2030-01-15"), 202).path("bagId").asText());
                    post(mapper, base + "/api/bags/" + id + "/retry", UUID.randomUUID().toString(), mapper.createObjectNode(), 409);
                    mapper.writeValue(directory.resolve("verified.json").toFile(), Map.of("bagId", id, "state", bag.state(), "node", bag.confirmedNodeId()));
                } finally {
                    simulator.destroy();
                    if (!simulator.waitFor(10, TimeUnit.SECONDS)) { simulator.destroyForcibly(); simulator.waitFor(5, TimeUnit.SECONDS); }
                }
            }
        }
    }
    static JsonNode post(ObjectMapper mapper, String url, String key, JsonNode body, int status) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").header("Origin", "http://127.0.0.1:5173").header("Idempotency-Key", key)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(status, response.statusCode(), response.body());
            return mapper.readTree(response.body());
        }
    }
    static ProcessBuilder python(Path directory, String mode) {
        Path root = Path.of("..").toAbsolutePath().normalize();
        var builder = new ProcessBuilder(root.resolve("simulator/.venv/bin/python").toString(), root.resolve("simulator/tests/phase6_driver.py").toString(),
                mode, directory.resolve("command.json").toString(), directory.resolve("reports.json").toString());
        builder.environment().put("PYTHONPATH", root.resolve("simulator/src").toString());
        builder.environment().put("VORTEX_COMMAND_JOURNAL", directory.resolve("journal.jsonl").toString());
        builder.environment().put("VORTEX_SIMULATOR_DELAY_SECONDS", "0");
        builder.environment().put("VORTEX_FAILURE_MODE", "SUCCESS");
        builder.environment().remove("VORTEX_EQUIPMENT_STATUS_JSON");
        builder.environment().remove("VORTEX_SCANNERS_JSON");
        return builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(directory.resolve("python.log").toFile()));
    }
    static void runPython(Path directory, String mode) throws Exception {
        Process child = python(directory, mode).start();
        try { assertTrue(child.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, child.exitValue(), Files.readString(directory.resolve("python.log"))); }
        finally { if (child.isAlive()) child.destroyForcibly().waitFor(5, TimeUnit.SECONDS); }
    }
}
