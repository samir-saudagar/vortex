package com.vortex.api.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.messaging.envelope.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommittedUpdateListenerTest {
    final ObjectMapper mapper = new ObjectMapper();
    final EventEnvelopeJsonCodec codec = new EventEnvelopeJsonCodec(mapper, new JsonEventEnvelopeValidator(mapper));
    final EventFactory events = new EventFactory(mapper, Clock.systemUTC());
    final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    final CommittedUpdateListener listener = new CommittedUpdateListener(codec, messaging);
    @Test void onlyCommittedStateAndEquipmentFactsAreForwardedWithTheUnchangedWireEnvelope() {
        UUID id = UUID.randomUUID(), correlation = UUID.randomUUID();
        var bag = events.bag(EventType.BAG_STATE_CHANGED, id, correlation, UUID.randomUUID(), events.payload(
                "bagId", id, "previousState", "IN_TRANSIT", "currentState", "AT_MAKEUP", "confirmedNodeId", "makeup-lhr",
                "stateVersion", 8, "reasonCode", "SCANNER_OBSERVATION_ACCEPTED", "historyId", UUID.randomUUID()));
        listener.receive(record(bag));
        verify(messaging).convertAndSend("/topic/bags/" + id, new String(codec.serialize(bag), java.nio.charset.StandardCharsets.UTF_8));
        var status = new EventEnvelope(UUID.randomUUID(), EventType.EQUIPMENT_STATUS_CHANGED, 1, Instant.now(), "vortex-backend",
                AggregateType.EQUIPMENT, "belt-west", correlation, UUID.randomUUID(), events.payload("equipmentId", "belt-west",
                "previousStatus", "AVAILABLE", "currentStatus", "BLOCKED", "changedAt", Instant.now().toString(), "sourceReportId", UUID.randomUUID()));
        listener.receive(record(status));
        verify(messaging).convertAndSend("/topic/equipment", new String(codec.serialize(status), java.nio.charset.StandardCharsets.UTF_8));
        var root = events.bag(EventType.BAG_CHECKED_IN, id, correlation, null, events.payload("bagId", id, "bagTag", "T", "flightId", "F", "checkInNodeId", "checkin-main", "checkedInAt", Instant.now().toString()));
        listener.receive(record(root));
        verifyNoMoreInteractions(messaging);
    }
    @Test void invalidMessageKeysCannotReachTheBrowser() {
        UUID id = UUID.randomUUID();
        var root = events.bag(EventType.BAG_CHECKED_IN, id, UUID.randomUUID(), null,
                events.payload("bagId", id, "bagTag", "T", "flightId", "F", "checkInNodeId", "checkin-main", "checkedInAt", Instant.now().toString()));
        assertThrows(EnvelopeValidationException.class, () -> listener.receive(new ConsumerRecord<>("workflow", 0, 0, "wrong", codec.serialize(root))));
        verifyNoInteractions(messaging);
    }
    ConsumerRecord<String, byte[]> record(EventEnvelope e) { return new ConsumerRecord<>("workflow", 0, 0, e.aggregateId(), codec.serialize(e)); }
}
