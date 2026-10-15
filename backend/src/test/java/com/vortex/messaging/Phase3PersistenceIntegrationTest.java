package com.vortex.messaging;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vortex.messaging.deduplication.ProcessingOutcome;
import com.vortex.messaging.deduplication.TransactionalEventConsumer;
import com.vortex.messaging.envelope.EventEnvelope;
import com.vortex.messaging.envelope.JsonEventEnvelopeValidator;
import com.vortex.messaging.outbox.OutboxRepository;
import com.vortex.messaging.outbox.OutboxWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.vortex.messaging.MessagingFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"vortex.outbox.enabled=false", "spring.kafka.listener.auto-startup=false"})
@EnabledIfEnvironmentVariable(named = "VORTEX_DB_INTEGRATION_TEST", matches = "true")
class Phase3PersistenceIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired OutboxWriter writer;
    @Autowired OutboxRepository outbox;
    @Autowired TransactionalEventConsumer consumer;
    private final List<String> equipmentIds = new ArrayList<>();
    private final List<String> consumerNames = new ArrayList<>();

    @BeforeEach
    void requireIsolatedDatabase() {
        assertTrue(jdbc.queryForObject("select current_database()", String.class).startsWith("vortex_test_"),
                "Phase 3 integration tests require the script's isolated temporary database");
    }

    @AfterEach
    void removeOnlyThisTestsTemporaryFixtures() {
        for (String id : equipmentIds) {
            jdbc.update("delete from vortex.outbox where aggregate_type = 'EQUIPMENT' and aggregate_id = ?", id);
            jdbc.update("delete from vortex.equipment where equipment_id = ?", id);
        }
        for (String name : consumerNames) jdbc.update("delete from vortex.processed_events where consumer_name = ?", name);
    }

    private String equipment() {
        String id = "phase3-" + UUID.randomUUID();
        jdbc.update("""
                insert into vortex.equipment (equipment_id, equipment_type, status, last_status_at)
                values (?, 'CONVEYOR', 'AVAILABLE', now())
                """, id);
        equipmentIds.add(id);
        return id;
    }

    private String group() {
        String group = "phase3-test-" + UUID.randomUUID();
        consumerNames.add(group);
        return group;
    }

    @Test
    void businessChangeSequenceAndOutboxInsertRollbackTogether() {
        String id = equipment();
        var event = equipmentChanged(id);
        assertThrows(IllegalStateException.class, () -> transactions.execute(status -> {
            jdbc.update("update vortex.equipment set status = 'BLOCKED' where equipment_id = ?", id);
            writer.append(event);
            throw new IllegalStateException("injected crash before database commit");
        }));
        assertEquals("AVAILABLE", jdbc.queryForObject("select status from vortex.equipment where equipment_id = ?", String.class, id));
        assertEquals(0L, jdbc.queryForObject("select event_sequence from vortex.equipment where equipment_id = ?", Long.class, id));
        assertEquals(0, jdbc.queryForObject("select count(*) from vortex.outbox where event_id = ?", Integer.class, event.eventId()));
        writer.append(event);
        assertEquals(1L, jdbc.queryForObject("select aggregate_sequence from vortex.outbox where event_id = ?", Long.class, event.eventId()));
    }

    @Test
    void lowestSequenceIsClaimedAndExpiredLeaseRepeatsSameEventBeforeTheNextOne() {
        String id = equipment();
        var first = equipmentChanged(id);
        var second = equipmentChanged(id);
        writer.append(first);
        writer.append(second);
        Instant now = Instant.now().plusSeconds(1);
        var claim = transactions.execute(status -> outbox.claimNext(now, Duration.ofSeconds(1)).orElseThrow());
        assertEquals(first.eventId().toString(), claim.eventId());
        assertEquals(1, claim.aggregateSequence());
        assertEquals(Boolean.TRUE, transactions.execute(status -> outbox.claimNext(now, Duration.ofSeconds(1)).isEmpty()));
        var recovered = transactions.execute(status -> outbox.claimNext(now.plusSeconds(2), Duration.ofSeconds(30)).orElseThrow());
        assertEquals(claim.outboxId(), recovered.outboxId());
        assertEquals(2, recovered.attempts());
        assertFalse(outbox.markPublished(claim, now));
        assertTrue(outbox.markPublished(recovered, now));
        var next = transactions.execute(status -> outbox.claimNext(now.plusSeconds(2), Duration.ofSeconds(30)).orElseThrow());
        assertEquals(second.eventId().toString(), next.eventId());
        assertEquals(2, next.aggregateSequence());
    }

    @Test
    void failedRowBlocksAggregateUntilExplicitRequeuePreservingIdentifiers() {
        String id = equipment();
        var first = equipmentChanged(id);
        writer.append(first);
        writer.append(equipmentChanged(id));
        Instant now = Instant.now().plusSeconds(1);
        var claim = transactions.execute(status -> outbox.claimNext(now, Duration.ofSeconds(30)).orElseThrow());
        assertTrue(outbox.markFailure(claim, now, now, true, "injected exhaustion"));
        assertEquals(Boolean.TRUE, transactions.execute(status -> outbox.claimNext(now.plusSeconds(60), Duration.ofSeconds(30)).isEmpty()));
        assertTrue(outbox.requeueFailed(claim.outboxId(), now));
        assertFalse(outbox.requeueFailed(claim.outboxId(), now));
        var recovered = transactions.execute(status -> outbox.claimNext(now, Duration.ofSeconds(30)).orElseThrow());
        assertEquals(claim.outboxId(), recovered.outboxId());
        assertEquals(first.eventId().toString(), recovered.eventId());
        assertEquals(1, recovered.aggregateSequence());
        assertEquals(1, recovered.attempts());
    }

    @Test
    void crashBeforeCommitRollsBackDedupEffectsAndFollowupOutboxThenReplayCommitsOnce() {
        String id = equipment();
        String group = group();
        var input = equipmentReport(id);
        var followup = equipmentChanged(id);
        assertThrows(IllegalStateException.class, () -> consumer.consume(group, input, () -> {
            jdbc.update("update vortex.equipment set status = 'BLOCKED' where equipment_id = ?", id);
            writer.append(followup);
            throw new IllegalStateException("injected consumer crash before commit");
        }));
        assertEquals(0, jdbc.queryForObject("select count(*) from vortex.processed_events where consumer_name = ?", Integer.class, group));
        assertEquals("AVAILABLE", jdbc.queryForObject("select status from vortex.equipment where equipment_id = ?", String.class, id));
        assertEquals(0L, jdbc.queryForObject("select event_sequence from vortex.equipment where equipment_id = ?", Long.class, id));
        assertTrue(consumer.consume(group, input, () -> {
            jdbc.update("update vortex.equipment set status = 'BLOCKED' where equipment_id = ?", id);
            writer.append(followup);
            return ProcessingOutcome.PROCESSED;
        }));
        // A crash after this committed return and before Kafka acknowledgement must be a no-op on replay.
        assertFalse(consumer.consume(group, input, () -> { throw new AssertionError("duplicate handler executed"); }));
        assertEquals(1, jdbc.queryForObject("select count(*) from vortex.outbox where aggregate_id = ?", Integer.class, id));
        assertEquals("PROCESSED", jdbc.queryForObject("select outcome from vortex.processed_events where consumer_name = ?", String.class, group));
    }

    @Test
    void semanticRejectionIsDurableAndReplayDoesNotRepeatTheDecision() {
        String group = group();
        var stale = scannerReport();
        assertTrue(consumer.consume(group, stale, () -> ProcessingOutcome.REJECTED));
        assertFalse(consumer.consume(group, stale, () -> { throw new AssertionError("rejected duplicate executed"); }));
        assertEquals("REJECTED", jdbc.queryForObject(
                "select outcome from vortex.processed_events where consumer_name = ? and event_id = ?", String.class, group, stale.eventId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from vortex.bags", Integer.class));
    }

    @Test
    void jsonbRoundTripDoesNotInflateAnEnvelopeAtTheApprovedByteLimit() {
        String id = equipment();
        var event = equipmentChanged(id);
        ObjectNode payload = (ObjectNode) event.payload();
        payload.put("padding", "");
        int base = CODEC.serialize(event).length;
        payload.put("padding", "x".repeat(JsonEventEnvelopeValidator.MAX_ENVELOPE_BYTES - base));
        assertEquals(JsonEventEnvelopeValidator.MAX_ENVELOPE_BYTES, CODEC.serialize(event).length);
        writer.append(event);
        var claim = transactions.execute(status -> outbox.claimNext(Instant.now().plusSeconds(1), Duration.ofSeconds(30)).orElseThrow());
        assertEquals(JsonEventEnvelopeValidator.MAX_ENVELOPE_BYTES, claim.envelope().length);
        assertEquals(event, CODEC.validate(claim.envelope()));
    }
}
