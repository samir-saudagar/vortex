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
    @org.springframework.boot.test.web.server.LocalServerPort int port;
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
        properties.add("vortex.kafka.groups.ui", () -> PREFIX + "-ui");
        properties.add("vortex.kafka.groups.simulator-reports", () -> REPORT_GROUP);
    }

    @Test void realRestOutboxKafkaPythonJourneyAndFinalReplay() throws Exception {
        assertTrue(jdbc.queryForObject("SELECT current_database()", String.class).startsWith("vortex_test_"));
        assertEquals(WORKFLOW, environment.getProperty("vortex.kafka.topics.workflow-events"));
        assertEquals("true", environment.getProperty("spring.kafka.listener.auto-startup"));
        assertEquals("true", environment.getProperty("vortex.outbox.enabled"));
        assertEquals(3, registry.getListenerContainers().size());
        assertTrue(registry.getListenerContainers().stream().allMatch(c -> c.isRunning()), "All Java listeners must be running");
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
        Process proxy = null;
        try {
            Path proxyLog = directory.resolve("vite.log");
            proxy = startViteProxy(root, proxyLog);
            String proxyUrl = proxyUrl(proxy, proxyLog);
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                assertTrue(simulator.isAlive(), () -> "Simulator exited: " + readLog(log));
                return readLog(log).contains("READY");
            });
            String browserOrigin = "http://127.0.0.1:5173";
            var response = browserRequest(proxyUrl, "/api/bags/check-in", browserOrigin, UUID.randomUUID().toString(),
                    new CheckInUseCase.Request("P4-KAFKA-" + UUID.randomUUID(), "FL-204-2030-01-15"));
            assertEquals(202, response.statusCode(), response.body());
            assertEquals(browserOrigin, response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
            var accepted = mapper.readValue(response.body(), CheckInUseCase.Accepted.class);
            assertEquals("CHECKED_IN", accepted.state());
            UUID id = accepted.bagId();
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
            for (String origin : List.of("http://localhost:5173", "http://127.0.0.1:5173")) {
                verifyCommittedStompNotification(id, finalBag.stateVersion(), proxyUrl, origin);
                verifyCommittedStompNotification(id, finalBag.stateVersion(), "http://127.0.0.1:" + port, origin);
                var read = browserRequest(proxyUrl, "/api/bags/" + id, origin, null, null);
                assertEquals(200, read.statusCode());
                assertEquals("AT_MAKEUP", mapper.readTree(read.body()).path("state").asText());
                var guardedRetry = browserRequest(proxyUrl, "/api/bags/" + id + "/retry", origin, UUID.randomUUID().toString(), Map.of());
                assertEquals(409, guardedRetry.statusCode());
                assertEquals("BAG_NOT_RETRYABLE", mapper.readTree(guardedRetry.body()).path("code").asText());
            }
            verifyCancelledFlightAndRecoveryThroughProxy(proxyUrl);
            assertEquals(403, browserRequest(proxyUrl, "/api/bags/check-in", "http://untrusted.example",
                    UUID.randomUUID().toString(), new CheckInUseCase.Request("P4-KAFKA-REJECTED", "FL-204-2030-01-15")).statusCode());
            verifyUntrustedWebSocketOriginRejected(proxyUrl);
            verifyUntrustedWebSocketOriginRejected("http://127.0.0.1:" + port);
            verifyClientSendRejected(proxyUrl);
            verifyClientSendRejected("http://127.0.0.1:" + port);
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
            if (proxy != null) {
                proxy.destroy();
                if (!proxy.waitFor(10, TimeUnit.SECONDS)) { proxy.destroyForcibly(); proxy.waitFor(5, TimeUnit.SECONDS); }
            }
            simulator.destroy();
            if (!simulator.waitFor(10, TimeUnit.SECONDS)) { simulator.destroyForcibly(); simulator.waitFor(5, TimeUnit.SECONDS); }
            System.out.println("Phase 4 Python process log: " + log);
            for (UUID id : jdbc.query("SELECT bag_id FROM vortex.bags WHERE bag_tag LIKE 'P4-KAFKA-%'", (rs, n) -> rs.getObject(1, UUID.class))) {
                Phase4DatabaseCleanup.bag(jdbc, transactions, id);
            }
            jdbc.update("DELETE FROM vortex.processed_events WHERE consumer_name IN (?, ?)", WORKFLOW_GROUP, REPORT_GROUP);
        }
    }
    private void verifyCommittedStompNotification(UUID id, long version, String proxyUrl, String origin) throws Exception {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.initialize();
        var socket = new org.springframework.web.socket.messaging.WebSocketStompClient(new org.springframework.web.socket.client.standard.StandardWebSocketClient());
        socket.setMessageConverter(new org.springframework.messaging.converter.StringMessageConverter());
        socket.setTaskScheduler(scheduler);
        var received = new java.util.concurrent.LinkedBlockingQueue<String>();
        try {
            var handshake = new org.springframework.web.socket.WebSocketHttpHeaders();
            handshake.setOrigin(origin);
            var session = socket.connectAsync(proxyUrl.replace("http:", "ws:") + "/ws", handshake,
                    new org.springframework.messaging.simp.stomp.StompHeaders(),
                    new org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter() {}).get(10, TimeUnit.SECONDS);
            var subscription = session.subscribe("/topic/bags/" + id, new org.springframework.messaging.simp.stomp.StompFrameHandler() {
                public java.lang.reflect.Type getPayloadType(org.springframework.messaging.simp.stomp.StompHeaders h) { return String.class; }
                public void handleFrame(org.springframework.messaging.simp.stomp.StompHeaders h, Object payload) { received.add((String) payload); }
            });
            // Replay an actual committed final state outbox envelope through the independent UI Kafka group.
            // It has no business effect; browser event/version dedup is tested separately.
            String committed = jdbc.queryForObject("SELECT envelope::text FROM vortex.outbox WHERE aggregate_id = ? AND event_type = 'BagStateChanged' AND envelope->'payload'->>'currentState' = 'AT_MAKEUP'", String.class, id.toString());
            // Spring's simple broker does not support STOMP subscription receipts.
            // Retry this harmless notification replay until the async subscription is installed.
            await().atMost(Duration.ofSeconds(15)).until(() -> {
                kafka.send(WORKFLOW, id.toString(), committed.getBytes(java.nio.charset.StandardCharsets.UTF_8)).get(5, TimeUnit.SECONDS);
                return !received.isEmpty();
            });
            String frame = received.poll();
            assertNotNull(frame, "Committed Kafka state must reach the real STOMP client");
            var notification = mapper.readTree(frame);
            assertEquals("BagStateChanged", notification.path("eventType").asText());
            assertEquals(id.toString(), notification.path("payload").path("bagId").asText());
            assertEquals("makeup-lhr", notification.path("payload").path("confirmedNodeId").asText());
            assertEquals(version, notification.path("payload").path("stateVersion").asLong());
            subscription.unsubscribe(); session.disconnect();
        } finally { socket.stop(); scheduler.shutdown(); }
    }
    private java.net.http.HttpResponse<String> browserRequest(String base, String path, String origin, String key, Object body) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + path))
                .timeout(Duration.ofSeconds(10)).header("Origin", origin);
        if (key != null) request.header("Idempotency-Key", key);
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        // Browsers use HTTP/1.1 for this plain-HTTP Vite proxy; avoid the JDK client's h2c upgrade.
        try (var client = java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build()) {
            return client.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        }
    }
    private void verifyCancelledFlightAndRecoveryThroughProxy(String proxyUrl) throws Exception {
        for (String origin : List.of("http://localhost:5173", "http://127.0.0.1:5173")) {
            var response = browserRequest(proxyUrl, "/api/bags/check-in", origin, UUID.randomUUID().toString(),
                    new CheckInUseCase.Request("P4-KAFKA-" + UUID.randomUUID(), "FL-999-2030-01-15"));
            assertEquals(202, response.statusCode(), response.body());
            var accepted = mapper.readValue(response.body(), CheckInUseCase.Accepted.class);
            assertEquals("CHECKED_IN", accepted.state(), "Cancelled flights must be durably accepted before async verification");
            await().atMost(Duration.ofSeconds(15)).until(() -> bags.find(accepted.bagId()).orElseThrow().state() == BagState.FLIGHT_VERIFICATION_FAILED);
            assertEquals("FLIGHT_NOT_ELIGIBLE", bags.find(accepted.bagId()).orElseThrow().failureCode());
            var retry = browserRequest(proxyUrl, "/api/bags/" + accepted.bagId() + "/retry", origin, UUID.randomUUID().toString(), Map.of());
            assertEquals(202, retry.statusCode(), retry.body());
            await().atMost(Duration.ofSeconds(15)).until(() -> bags.find(accepted.bagId()).orElseThrow().state() == BagState.FLIGHT_VERIFICATION_FAILED);
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM vortex.bag_history WHERE bag_id = ? AND record_type = 'RETRY_REQUEST'", Integer.class, accepted.bagId()));
            await().atMost(Duration.ofSeconds(15)).until(() -> jdbc.queryForObject("SELECT count(*) FROM vortex.outbox WHERE aggregate_id = ? AND status <> 'PUBLISHED'", Integer.class, accepted.bagId().toString()) == 0);
        }
    }
    private Process startViteProxy(Path root, Path log) throws Exception {
        // Load the actual Vite config; change only backend target ports for this isolated Spring server.
        String script = """
                import { createServer } from 'vite';
                const port = process.argv[1];
                const server = await createServer({ server: { host: '127.0.0.1', port: 0, strictPort: true,
                  proxy: { '/api': { target: `http://127.0.0.1:${port}` }, '/ws': { target: `ws://127.0.0.1:${port}` } } } });
                await server.listen();
                console.log('VORTEX_PROXY_PORT=' + server.httpServer.address().port);
                process.on('SIGTERM', async () => { await server.close(); process.exit(0); });
                """;
        return new ProcessBuilder("node", "--input-type=module", "-e", script, Integer.toString(port))
                .directory(root.resolve("frontend").toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
    private String proxyUrl(Process proxy, Path log) {
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            assertTrue(proxy.isAlive(), () -> "Vite exited: " + readLog(log));
            return readLog(log).contains("VORTEX_PROXY_PORT=");
        });
        var matcher = java.util.regex.Pattern.compile("VORTEX_PROXY_PORT=(\\d+)").matcher(readLog(log));
        assertTrue(matcher.find(), readLog(log));
        return "http://127.0.0.1:" + matcher.group(1);
    }
    private void verifyUntrustedWebSocketOriginRejected(String proxyUrl) throws Exception {
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> client.newWebSocketBuilder()
                    .header("Origin", "http://untrusted.example").buildAsync(java.net.URI.create(proxyUrl.replace("http:", "ws:") + "/ws"),
                            new java.net.http.WebSocket.Listener() {}).get(10, TimeUnit.SECONDS));
            var handshake = assertInstanceOf(java.net.http.WebSocketHandshakeException.class, failure.getCause());
            assertEquals(403, handshake.getResponse().statusCode());
        }
    }
    private void verifyClientSendRejected(String baseUrl) throws Exception {
        var frames = new java.util.concurrent.LinkedBlockingQueue<String>();
        var closed = new java.util.concurrent.CompletableFuture<Integer>();
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            var socket = client.newWebSocketBuilder().header("Origin", "http://127.0.0.1:5173")
                    .buildAsync(java.net.URI.create(baseUrl.replace("http:", "ws:") + "/ws"), new java.net.http.WebSocket.Listener() {
                        private final StringBuilder frame = new StringBuilder();
                        @Override public void onOpen(java.net.http.WebSocket webSocket) { webSocket.request(1); }
                        @Override public java.util.concurrent.CompletionStage<?> onText(java.net.http.WebSocket webSocket, CharSequence data, boolean last) {
                            frame.append(data);
                            if (last) { frames.add(frame.toString()); frame.setLength(0); }
                            webSocket.request(1);
                            return null;
                        }
                        @Override public java.util.concurrent.CompletionStage<?> onClose(java.net.http.WebSocket webSocket, int status, String reason) {
                            closed.complete(status);
                            return null;
                        }
                        @Override public void onError(java.net.http.WebSocket webSocket, Throwable error) { closed.completeExceptionally(error); }
                    }).get(10, TimeUnit.SECONDS);
            try {
                socket.sendText("CONNECT\naccept-version:1.2\nhost:localhost\nheart-beat:0,0\n\n\0", true).get(5, TimeUnit.SECONDS);
                String connected = frames.poll(10, TimeUnit.SECONDS);
                assertNotNull(connected, "An allowed origin must establish STOMP before testing SEND rejection");
                assertTrue(connected.startsWith("CONNECTED\n"), connected);
                socket.sendText("SEND\ndestination:/topic/equipment\ncontent-type:text/plain\n\nforbidden-client-publication\0", true).get(5, TimeUnit.SECONDS);
                String error = frames.poll(10, TimeUnit.SECONDS);
                assertNotNull(error, "Client SEND must be rejected over the actual WebSocket connection");
                assertTrue(error.startsWith("ERROR\n"), error);
                assertNotNull(closed.get(10, TimeUnit.SECONDS), "Rejected client publication must close its STOMP session");
            } finally { socket.abort(); }
        }
    }
    private static String readLog(Path file) {
        try { return Files.readString(file); } catch (java.io.IOException e) { return e.toString(); }
    }
}
