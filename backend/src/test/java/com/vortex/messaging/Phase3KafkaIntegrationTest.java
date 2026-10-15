package com.vortex.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vortex.messaging.deduplication.*;
import com.vortex.messaging.envelope.*;
import com.vortex.messaging.kafka.SimulatorReportKafkaListener;
import com.vortex.messaging.outbox.*;
import com.vortex.messaging.workflow.*;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.utils.AppInfoParser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

import static com.vortex.messaging.MessagingFixtures.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "vortex.outbox.enabled=false", "spring.kafka.listener.auto-startup=false",
        "vortex.kafka.topics.quarantine=vortex.phase3-test.quarantine",
        "logging.level.org.apache.kafka=WARN", "logging.level.org.springframework.kafka=WARN"})
@EnabledIfEnvironmentVariable(named = "VORTEX_PHASE3_KAFKA_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Phase3KafkaIntegrationTest {
    private static final String WORKFLOW = "vortex.phase3-test.workflow";
    private static final String REPORTS = "vortex.phase3-test.reports";
    private static final String QUARANTINE = "vortex.phase3-test.quarantine";
    private static final Duration TIMEOUT = Duration.ofSeconds(40);
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired OutboxWriter writer;
    @Autowired OutboxRepository outbox;
    @Autowired TransactionalEventConsumer consumer;
    @Autowired KafkaTemplate<String, byte[]> kafka;
    @Autowired ConcurrentKafkaListenerContainerFactory<String, byte[]> factory;
    private Admin admin;
    private final List<String> equipmentIds = new ArrayList<>();
    private final List<String> groups = new ArrayList<>();

    @BeforeAll
    void initializeDedicatedDiagnosticTopics() throws Exception {
        assertTrue(jdbc.queryForObject("select current_database()", String.class).startsWith("vortex_test_"));
        assertEquals("3.9.2", AppInfoParser.getVersion());
        admin = Admin.create(Map.of("bootstrap.servers", bootstrap(), "default.api.timeout.ms", 30_000));
        var existing = admin.listTopics().names().get(30, TimeUnit.SECONDS);
        var desired = Map.of(WORKFLOW, 3, REPORTS, 3, "vortex.phase3-test.commands", 3, QUARANTINE, 1);
        var missing = desired.entrySet().stream().filter(entry -> !existing.contains(entry.getKey()))
                .map(entry -> new NewTopic(entry.getKey(), entry.getValue(), (short) 1)
                        .configs(Map.of("retention.ms", "604800000", "max.message.bytes",
                                entry.getKey().equals(QUARANTINE) ? "33554432" : "2097152"))).toList();
        if (!missing.isEmpty()) admin.createTopics(missing).all().get(30, TimeUnit.SECONDS);
        // Upgrade only this diagnostic topic in place; preserve existing records and other settings.
        admin.incrementalAlterConfigs(Map.of(new ConfigResource(ConfigResource.Type.TOPIC, QUARANTINE),
                List.of(new AlterConfigOp(new ConfigEntry("max.message.bytes", "33554432"), AlterConfigOp.OpType.SET))))
                .all().get(30, TimeUnit.SECONDS);
        assertEquals("33554432", ((org.springframework.kafka.core.DefaultKafkaProducerFactory<?, ?>)
                kafka.getProducerFactory()).getConfigurationProperties().get("max.request.size").toString());
        System.out.println("Phase 3 Java integration: Kafka client=" + AppInfoParser.getVersion() + "; existing broker expected apache/kafka:4.3.1");
    }

    @AfterAll
    void closeAdmin() {
        if (admin != null) admin.close();
    }

    @AfterEach
    void removeOnlyOwnTemporaryDatabaseFixtures() {
        for (String id : equipmentIds) {
            jdbc.update("delete from vortex.outbox where aggregate_type = 'EQUIPMENT' and aggregate_id = ?", id);
            jdbc.update("delete from vortex.equipment where equipment_id = ?", id);
        }
        equipmentIds.clear();
        for (String group : groups) jdbc.update("delete from vortex.processed_events where consumer_name = ?", group);
        groups.clear();
    }

    private String equipment() {
        String id = "phase3-" + UUID.randomUUID();
        jdbc.update("insert into vortex.equipment (equipment_id, equipment_type, status, last_status_at) values (?, 'CONVEYOR', 'AVAILABLE', now())", id);
        equipmentIds.add(id);
        return id;
    }

    private String group() {
        String id = "vortex-phase3-test-" + UUID.randomUUID();
        groups.add(id);
        return id;
    }

    private void appendForDiagnosticTopic(EventEnvelope event) {
        long id = writer.append(event);
        // This is an isolated test database. Keep diagnostic messages off the application topics.
        jdbc.update("update vortex.outbox set topic = ? where outbox_id = ?", WORKFLOW, id);
    }

    private OutboxRelay relay(KafkaTemplate<String, byte[]> producer, Instant now) {
        return new OutboxRelay(outbox, producer, transactions, 30, 10, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void committedOutboxPublishesInAggregateOrderAndMarksOnlyAcknowledgedRows() throws Exception {
        String id = equipment();
        var first = equipmentChanged(id);
        var second = equipmentChanged(id);
        try (var observer = observer(WORKFLOW)) {
            appendForDiagnosticTopic(first);
            appendForDiagnosticTopic(second);
            relay(kafka, Instant.now().plusSeconds(1)).relayDueRows();
            var received = read(observer, row -> json(row.value()).path("aggregateId").asText().equals(id), 2);
            assertEquals(List.of(first.eventId().toString(), second.eventId().toString()), received.stream()
                    .map(row -> CODEC.validate(row.value(), row.key()).eventId().toString()).toList());
            assertEquals(received.get(0).partition(), received.get(1).partition());
            assertTrue(received.get(0).offset() < received.get(1).offset());
            assertEquals(2, jdbc.queryForObject("select count(*) from vortex.outbox where aggregate_id = ? and status = 'PUBLISHED'", Integer.class, id));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void injectedBrokerOutageLeavesDurableRetryThenRealBrokerAcknowledgementPublishes() throws Exception {
        String id = equipment();
        var event = equipmentChanged(id);
        try (var observer = observer(WORKFLOW)) {
            appendForDiagnosticTopic(event);
            KafkaTemplate<String, byte[]> unavailable = mock(KafkaTemplate.class);
            when(unavailable.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                    .thenReturn(CompletableFuture.failedFuture(new org.apache.kafka.common.errors.TimeoutException("injected outage")));
            Instant now = Instant.now().plusSeconds(1);
            relay(unavailable, now).relayDueRows();
            assertEquals("PENDING", jdbc.queryForObject("select status from vortex.outbox where event_id = ?", String.class, event.eventId()));
            assertEquals(1, jdbc.queryForObject("select attempts from vortex.outbox where event_id = ?", Integer.class, event.eventId()));
            relay(kafka, now.plusSeconds(2)).relayDueRows();
            assertEquals(event.eventId(), CODEC.validate(read(observer, row -> row.key().equals(id), 1).getFirst().value()).eventId());
            assertEquals("PUBLISHED", jdbc.queryForObject("select status from vortex.outbox where event_id = ?", String.class, event.eventId()));
        }
    }

    @Test
    void crashAfterPublishAckRecoversExpiredLeaseAsSameEventBeforeLaterSequence() throws Exception {
        String id = equipment();
        var first = equipmentChanged(id);
        var second = equipmentChanged(id);
        try (var observer = observer(WORKFLOW)) {
            appendForDiagnosticTopic(first);
            appendForDiagnosticTopic(second);
            Instant now = Instant.now().plusSeconds(1);
            var claim = transactions.execute(status -> outbox.claimNext(now, Duration.ofSeconds(1)).orElseThrow());
            kafka.send(claim.topic(), claim.messageKey(), claim.envelope()).get(10, TimeUnit.SECONDS);
            // Simulate process death here: do not persist PUBLISHED after the actual broker acknowledgement.
            assertEquals(Boolean.TRUE, transactions.execute(status -> outbox.claimNext(now, Duration.ofSeconds(1)).isEmpty()));
            relay(kafka, now.plusSeconds(2)).relayDueRows();
            var received = read(observer, row -> row.key().equals(id), 3);
            assertEquals(List.of(first.eventId().toString(), first.eventId().toString(), second.eventId().toString()),
                    received.stream().map(row -> json(row.value()).get("eventId").asText()).toList());
            assertEquals(2, jdbc.queryForObject("select attempts from vortex.outbox where event_id = ?", Integer.class, first.eventId()));
        }
    }

    @Test
    void postgresCommitPrecedesOffsetAndReplayedEventDoesNotRepeatEffects() throws Exception {
        String id = equipment();
        String group = group();
        var event = equipmentReport(id);
        AtomicInteger effects = new AtomicInteger();
        var listener = reportListener(group, input -> {
            assertEquals(0, jdbc.queryForObject("select count(*) from vortex.processed_events where consumer_name = ? and outcome = 'PROCESSED'", Integer.class, group));
            effects.incrementAndGet();
            jdbc.update("update vortex.equipment set status_reason = 'PHASE3_TEST_EFFECT' where equipment_id = ?", id);
            return ProcessingOutcome.PROCESSED;
        });
        var container = start(group, record -> {
            assertTrue(committed(group, new TopicPartition(record.topic(), record.partition())) <= record.offset(),
                    "source offset must not commit before the listener transaction");
            listener.receive(record);
        });
        try {
            RecordMetadata first = publish(event);
            awaitCommitted(group, first);
            assertEquals("PROCESSED", jdbc.queryForObject("select outcome from vortex.processed_events where consumer_name = ?", String.class, group));
            RecordMetadata duplicate = publish(event);
            awaitCommitted(group, duplicate);
            assertEquals(1, effects.get());
            assertEquals("PHASE3_TEST_EFFECT", jdbc.queryForObject("select status_reason from vortex.equipment where equipment_id = ?", String.class, id));
        } finally {
            container.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {9, 1})
    void invalidVersionAndRepeatedPoisonReachQuarantineAfterExactlyFourAttempts(int version) throws Exception {
        String group = group();
        var event = equipmentReport("phase3-poison-" + UUID.randomUUID());
        ObjectNode original = (ObjectNode) MAPPER.readTree(CODEC.serialize(event));
        original.put("eventVersion", version);
        if (version == 9) {
            ((ObjectNode) original.get("payload")).put("padding", "");
            int base = MAPPER.writeValueAsBytes(original).length;
            ((ObjectNode) original.get("payload")).put("padding", "x".repeat(JsonEventEnvelopeValidator.MAX_ENVELOPE_BYTES - base));
            assertEquals(1_048_576, MAPPER.writeValueAsBytes(original).length);
        }
        var listener = reportListener(group, input -> { throw new IllegalStateException("injected repeated poison"); });
        AtomicInteger attempts = new AtomicInteger();
        try (var observer = observer(QUARANTINE)) {
            var container = start(group, record -> { attempts.incrementAndGet(); listener.receive(record); });
            try {
                RecordMetadata source = kafka.send(REPORTS, event.aggregateId(), MAPPER.writeValueAsBytes(original)).get(10, TimeUnit.SECONDS).getRecordMetadata();
                var quarantined = read(observer, row -> json(row.value()).path("originalEnvelope").path("eventId").asText().equals(event.eventId().toString()), 1);
                JsonNode wrapper = json(quarantined.getFirst().value());
                assertEquals(4, attempts.get());
                assertEquals(4, wrapper.get("attempts").asInt());
                assertEquals(group, wrapper.get("consumerGroup").asText());
                assertEquals(source.partition(), wrapper.get("sourcePartition").asInt());
                assertEquals(source.offset(), wrapper.get("sourceOffset").asLong());
                assertEquals(original, wrapper.get("originalEnvelope"));
                assertEquals(version == 9 ? "UNSUPPORTED_EVENT_VERSION" : "CONSUMER_PROCESSING_FAILED", wrapper.get("failureCode").asText());
                awaitCommitted(group, source);
                assertEquals(0, jdbc.queryForObject("select count(*) from vortex.processed_events where consumer_name = ?", Integer.class, group));
            } finally {
                container.stop();
            }
        }
    }

    @Test
    void semanticRejectedOutcomeCommitsWithoutRetryOrQuarantine() throws Exception {
        String group = group();
        var stale = scannerReport();
        AtomicInteger decisions = new AtomicInteger();
        var listener = reportListener(group, input -> { decisions.incrementAndGet(); return ProcessingOutcome.REJECTED; });
        var container = start(group, listener::receive);
        try {
            var source = publish(stale);
            awaitCommitted(group, source);
            assertEquals(1, decisions.get());
            assertEquals("REJECTED", jdbc.queryForObject("select outcome from vortex.processed_events where consumer_name = ?", String.class, group));
            assertEquals(0, jdbc.queryForObject("select count(*) from vortex.bags", Integer.class));
        } finally {
            container.stop();
        }
    }

    @Test
    void alternatingExceptionTypesQuarantineOnTheFourthFailure() throws Exception {
        String group = group();
        var event = equipmentReport("phase3-alternating-" + UUID.randomUUID());
        AtomicInteger attempts = new AtomicInteger();
        var listener = reportListener(group, input -> {
            if (attempts.incrementAndGet() % 2 == 1) throw new IllegalArgumentException("injected poison");
            throw new IllegalStateException("injected poison");
        });
        try (var observer = observer(QUARANTINE)) {
            var container = start(group, listener::receive);
            try {
                var source = publish(event);
                JsonNode wrapper = json(read(observer, row -> event.aggregateId().equals(row.key()), 1).getFirst().value());
                awaitCommitted(group, source);
                assertEquals(4, attempts.get());
                assertEquals(4, wrapper.get("attempts").asInt());
                assertEquals(group, wrapper.get("consumerGroup").asText());
                assertEquals(IllegalStateException.class.getName(), wrapper.get("failureClass").asText());
                assertEquals(0, jdbc.queryForObject("select count(*) from vortex.processed_events where consumer_name = ?", Integer.class, group));
            } finally {
                container.stop();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"1600000,false", "2096128,false", "1,true"})
    void malformedSourceRecordsAndKeysReachQuarantineWithoutTruncation(int size, boolean largeKey) throws Exception {
        String group = group();
        String key = largeKey ? group + String.valueOf((char) 0).repeat(2_096_128 - group.length())
                : "phase3-malformed-" + UUID.randomUUID();
        byte[] original = new byte[size];
        Arrays.fill(original, (byte) 0xff);
        AtomicInteger attempts = new AtomicInteger();
        var listener = reportListener(group, input -> { throw new AssertionError("malformed input reached handler"); });
        try (var observer = observer(QUARANTINE)) {
            var container = start(group, record -> { attempts.incrementAndGet(); listener.receive(record); });
            try {
                var source = kafka.send(REPORTS, key, original).get(10, TimeUnit.SECONDS).getRecordMetadata();
                var record = read(observer, row -> key.equals(row.key()), 1).getFirst();
                JsonNode wrapper = json(record.value());
                assertTrue(record.value().length > 2_097_152);
                assertArrayEquals(original, Base64.getDecoder().decode(wrapper.get("originalRawBase64").asText()));
                assertEquals(key, wrapper.get("originalKey").asText());
                assertEquals(largeKey ? "INVALID_UTF8" : "ENVELOPE_TOO_LARGE", wrapper.get("failureCode").asText());
                assertEquals(source.offset(), wrapper.get("sourceOffset").asLong());
                assertEquals(source.partition(), wrapper.get("sourcePartition").asInt());
                assertEquals(group, wrapper.get("consumerGroup").asText());
                assertEquals(4, attempts.get());
                awaitCommitted(group, source);
                assertEquals(0, jdbc.queryForObject("select count(*) from vortex.processed_events where consumer_name = ?", Integer.class, group));
            } finally {
                container.stop();
            }
        }
    }

    @Test
    void injectedDatabaseOutageBeyondFourAttemptsRetainsOffsetAndDoesNotQuarantine() throws Exception {
        String group = group();
        var event = equipmentReport("phase3-outage-" + UUID.randomUUID());
        AtomicInteger attempts = new AtomicInteger();
        var listener = reportListener(group, input -> {
            if (attempts.incrementAndGet() <= 4) throw new CannotGetJdbcConnectionException("injected temporary PostgreSQL outage");
            return ProcessingOutcome.PROCESSED;
        });
        var container = start(group, listener::receive);
        try {
            var source = publish(event);
            awaitCommitted(group, source);
            assertEquals(5, attempts.get());
            assertEquals("PROCESSED", jdbc.queryForObject("select outcome from vortex.processed_events where consumer_name = ?", String.class, group));
        } finally {
            container.stop();
        }
    }

    private SimulatorReportKafkaListener reportListener(String group, Function<EventEnvelope, ProcessingOutcome> callback) {
        var handler = new SimulatorReportHandler() {
            public Set<EventType> eventTypes() {
                return EnumSet.of(EventType.EQUIPMENT_COMMAND_RESULT, EventType.SCANNER_OBSERVATION, EventType.EQUIPMENT_STATUS_REPORTED);
            }
            public ProcessingOutcome handle(EventEnvelope event) { return callback.apply(event); }
        };
        return new SimulatorReportKafkaListener(CODEC, consumer, new SimulatorReportDispatcher(List.of(handler)), group);
    }

    private org.springframework.kafka.listener.ConcurrentMessageListenerContainer<String, byte[]> start(
            String group, MessageListener<String, byte[]> listener) throws Exception {
        var container = factory.createContainer(REPORTS);
        container.setBeanName(group);
        container.getContainerProperties().setGroupId(group);
        CountDownLatch assigned = new CountDownLatch(1);
        container.getContainerProperties().setConsumerRebalanceListener(new ConsumerAwareRebalanceListener() {
            @Override
            public void onPartitionsAssigned(Consumer<?, ?> kafkaConsumer, Collection<TopicPartition> partitions) {
                kafkaConsumer.seekToEnd(partitions);
                for (TopicPartition partition : partitions) kafkaConsumer.position(partition);
                assigned.countDown();
            }
        });
        container.setupMessageListener(listener);
        container.start();
        if (!assigned.await(30, TimeUnit.SECONDS)) {
            container.stop();
            fail("diagnostic listener did not obtain its partitions");
        }
        return container;
    }

    private RecordMetadata publish(EventEnvelope event) throws Exception {
        return kafka.send(REPORTS, event.aggregateId(), CODEC.serialize(event)).get(10, TimeUnit.SECONDS).getRecordMetadata();
    }

    private void awaitCommitted(String group, RecordMetadata source) {
        await().atMost(TIMEOUT).until(() -> committed(group, new TopicPartition(source.topic(), source.partition())) >= source.offset() + 1);
    }

    private long committed(String group, TopicPartition partition) {
        try {
            var offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            return offsets.containsKey(partition) ? offsets.get(partition).offset() : -1L;
        } catch (Exception exception) {
            throw new IllegalStateException("could not inspect diagnostic consumer offsets", exception);
        }
    }

    private KafkaConsumer<String, byte[]> observer(String topic) {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", bootstrap());
        properties.put("enable.auto.commit", false);
        properties.put("key.deserializer", StringDeserializer.class.getName());
        properties.put("value.deserializer", ByteArrayDeserializer.class.getName());
        var observer = new KafkaConsumer<String, byte[]>(properties);
        var partitions = observer.partitionsFor(topic).stream().map(partition -> new TopicPartition(topic, partition.partition())).toList();
        observer.assign(partitions);
        observer.seekToEnd(partitions);
        partitions.forEach(observer::position);
        return observer;
    }

    private List<ConsumerRecord<String, byte[]>> read(KafkaConsumer<String, byte[]> observer,
                                                     Predicate<ConsumerRecord<String, byte[]>> matches, int count) {
        List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (found.size() < count && System.nanoTime() < deadline) {
            for (var record : observer.poll(Duration.ofMillis(100))) if (matches.test(record)) found.add(record);
        }
        assertEquals(count, found.size(), "expected acknowledged diagnostic Kafka records");
        return found;
    }

    private static JsonNode json(byte[] value) {
        try { return MAPPER.readTree(value); }
        catch (Exception exception) { throw new IllegalArgumentException(exception); }
    }

    private static String bootstrap() {
        return System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
    }
}
