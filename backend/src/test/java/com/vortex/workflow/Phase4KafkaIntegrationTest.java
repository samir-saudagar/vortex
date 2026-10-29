package com.vortex.workflow;

import com.fasterxml.jackson.databind.*;
import com.vortex.bagstate.api.BagQuery;
import com.vortex.bagstate.domain.BagState;
import com.vortex.checkin.api.CheckInUseCase;
import com.vortex.messaging.envelope.*;
import com.vortex.movement.api.MovementQuery;
import com.vortex.routing.api.RouteQuery;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.listener.auto-startup=true", "vortex.outbox.enabled=true", "vortex.movement.sweeper-enabled=true",
        "vortex.outbox.poll-interval-ms=50", "logging.level.org.apache.kafka=WARN", "logging.level.org.springframework.kafka=WARN"})
@EnabledIfEnvironmentVariable(named = "VORTEX_PHASE4_KAFKA_TEST", matches = "true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class Phase4KafkaIntegrationTest {
    static final String PREFIX = "vortex.phase4-test." + UUID.randomUUID();
    static final String WORKFLOW = PREFIX + ".workflow", COMMANDS = PREFIX + ".commands", REPORTS = PREFIX + ".reports", QUARANTINE = PREFIX + ".quarantine";
    static final String WORKFLOW_GROUP = PREFIX + "-workflow", REPORT_GROUP = PREFIX + "-reports";
    @Autowired TestRestTemplate rest;
    @Autowired BagQuery bags;
    @Autowired RouteQuery routes;
    @Autowired MovementQuery movement;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired ObjectMapper mapper;
    @Autowired EventEnvelopeJsonCodec codec;
    @Autowired KafkaTemplate<String, byte[]> kafka;
    @Autowired org.springframework.core.env.Environment environment;
    @Autowired org.springframework.kafka.config.KafkaListenerEndpointRegistry registry;

    static String bootstrap() { return System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"); }
    @DynamicPropertySource static void isolatedTopics(DynamicPropertyRegistry properties) throws Exception {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", bootstrap()))) {
            admin.createTopics(List.of(new NewTopic(WORKFLOW, 3, (short) 1), new NewTopic(COMMANDS, 3, (short) 1),
                    new NewTopic(REPORTS, 3, (short) 1), new NewTopic(QUARANTINE, 1, (short) 1)))
                    .all().get(30, TimeUnit.SECONDS);
        }
        properties.add("vortex.kafka.topics.workflow-events", () -> WORKFLOW);
        properties.add("vortex.kafka.topics.equipment-commands", () -> COMMANDS);
        properties.add("vortex.kafka.topics.simulator-reports", () -> REPORTS);
        properties.add("vortex.kafka.topics.quarantine", () -> QUARANTINE);
        properties.add("vortex.kafka.groups.workflow", () -> WORKFLOW_GROUP);
        properties.add("vortex.kafka.groups.simulator-reports", () -> REPORT_GROUP);
    }

    @Test void realRestOutboxKafkaPythonJourneyAndFinalReplay() throws Exception {
        assertTrue(jdbc.queryForObject("SELECT current_database()", String.class).startsWith("vortex_test_"));
        assertEquals(WORKFLOW, environment.getProperty("vortex.kafka.topics.workflow-events"));
        assertEquals("true", environment.getProperty("spring.kafka.listener.auto-startup"));
        assertEquals("true", environment.getProperty("vortex.outbox.enabled"));
        assertEquals(2, registry.getListenerContainers().size());
        assertTrue(registry.getListenerContainers().stream().allMatch(c -> c.isRunning()), "Both Java listeners must be running");
        Path root = Path.of("..").toAbsolutePath().normalize();
        Path python = root.resolve("simulator/.venv/bin/python");
        assertTrue(Files.isExecutable(python), "Run the documented locked Python environment setup first");
        Path directory = Files.createTempDirectory("vortex-phase4-simulator-");
        Path log = directory.resolve("simulator.log");
        ProcessBuilder builder = new ProcessBuilder(python.toString(), "-m", "vortex_simulator.main").directory(root.toFile());
        var env = builder.environment();
        env.put("PYTHONPATH", root.resolve("simulator/src").toString());
        env.put("KAFKA_BOOTSTRAP_SERVERS", bootstrap()); env.put("VORTEX_COMMAND_TOPIC", COMMANDS);
        env.put("VORTEX_REPORT_TOPIC", REPORTS); env.put("VORTEX_QUARANTINE_TOPIC", QUARANTINE);
        env.put("VORTEX_SIMULATOR_GROUP", PREFIX + "-python"); env.put("VORTEX_COMMAND_JOURNAL", directory.resolve("journal.jsonl").toString());
        env.put("VORTEX_SIMULATOR_DELAY_SECONDS", "0"); env.put("VORTEX_FAILURE_MODE", "SUCCESS");
        env.remove("VORTEX_EQUIPMENT_STATUS_JSON"); env.remove("VORTEX_SCANNERS_JSON");
        Process simulator = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                assertTrue(simulator.isAlive(), () -> "Simulator exited: " + readLog(log));
                return readLog(log).contains("READY");
            });
            HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Idempotency-Key", UUID.randomUUID().toString());
            var response = rest.exchange("/api/bags/check-in", HttpMethod.POST,
                    new HttpEntity<>(new CheckInUseCase.Request("P4-KAFKA-" + UUID.randomUUID(), "FL-204-2030-01-15"), headers), CheckInUseCase.Accepted.class);
            assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
            UUID id = Objects.requireNonNull(response.getBody()).bagId();
            try {
                await().atMost(Duration.ofSeconds(35)).until(() -> bags.find(id).orElseThrow().state() == BagState.AT_MAKEUP);
            } catch (org.awaitility.core.ConditionTimeoutException failure) {
                throw new AssertionError("Journey stalled: " + bags.find(id) + " outbox=" + jdbc.queryForList(
                        "SELECT event_type, topic, status, attempts, last_error FROM vortex.outbox WHERE aggregate_id = ? ORDER BY aggregate_sequence", id.toString())
                        + " history=" + bags.history(id) + " simulator=" + readLog(log), failure);
            }
            var finalBag = bags.find(id).orElseThrow();
            assertEquals("makeup-lhr", finalBag.confirmedNodeId());
            assertEquals("COMPLETE", routes.latest(id).orElseThrow().status());
            await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject("""
                    SELECT count(*) FROM vortex.equipment e JOIN vortex.processed_events p ON p.event_id = e.last_report_id
                    WHERE p.consumer_name = ? AND p.outcome = 'PROCESSED'
                    """, Integer.class, REPORT_GROUP) == 9);
            assertEquals(2, movement.commands(id).size()); assertEquals(2, movement.observations(id).size());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM vortex.bag_history WHERE bag_id = ? AND current_state = 'AT_INTERMEDIATE_NODE'", Integer.class, id));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM vortex.bag_history WHERE bag_id = ? AND current_state = 'AT_MAKEUP'", Integer.class, id));
            var observation = jdbc.queryForMap("SELECT * FROM vortex.equipment_observations WHERE bag_id = ? AND node_id = 'makeup-lhr'", id);
            EventEnvelope replay = new EventEnvelope((UUID) observation.get("event_id"), EventType.SCANNER_OBSERVATION, 1, Instant.now(),
                    "python-equipment-simulator", AggregateType.BAG, id.toString(), finalBag.correlationId(),
                    (UUID) observation.get("command_id"), mapper.readTree(observation.get("validated_payload").toString()));
            var metadata = kafka.send(REPORTS, id.toString(), codec.serialize(replay)).get(10, TimeUnit.SECONDS).getRecordMetadata();
            try (Admin admin = Admin.create(Map.of("bootstrap.servers", bootstrap()))) {
                await().atMost(Duration.ofSeconds(15)).until(() -> {
                    var offsets = admin.listConsumerGroupOffsets(REPORT_GROUP).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                    var offset = offsets.get(new TopicPartition(REPORTS, metadata.partition()));
                    return offset != null && offset.offset() > metadata.offset();
                });
            }
            assertEquals(finalBag.stateVersion(), bags.find(id).orElseThrow().stateVersion());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM vortex.bag_history WHERE bag_id = ? AND current_state = 'AT_MAKEUP'", Integer.class, id));
            assertEquals(HttpStatus.OK, rest.getForEntity("/api/bags/" + id + "/history", JsonNode.class).getStatusCode());
            assertEquals(HttpStatus.OK, rest.getForEntity("/actuator/health/readiness", JsonNode.class).getStatusCode());
            await().atMost(Duration.ofSeconds(15)).until(() -> jdbc.queryForObject("SELECT count(*) FROM vortex.outbox WHERE aggregate_id = ? AND status <> 'PUBLISHED'", Integer.class, id.toString()) == 0);
        } finally {
            simulator.destroy();
            if (!simulator.waitFor(10, TimeUnit.SECONDS)) { simulator.destroyForcibly(); simulator.waitFor(5, TimeUnit.SECONDS); }
            System.out.println("Phase 4 Python process log: " + log);
            for (UUID id : jdbc.query("SELECT bag_id FROM vortex.bags WHERE bag_tag LIKE 'P4-KAFKA-%'", (rs, n) -> rs.getObject(1, UUID.class))) {
                Phase4DatabaseCleanup.bag(jdbc, transactions, id);
            }
            jdbc.update("DELETE FROM vortex.processed_events WHERE consumer_name IN (?, ?)", WORKFLOW_GROUP, REPORT_GROUP);
        }
    }
    private static String readLog(Path file) {
        try { return Files.readString(file); } catch (java.io.IOException e) { return e.toString(); }
    }
}
