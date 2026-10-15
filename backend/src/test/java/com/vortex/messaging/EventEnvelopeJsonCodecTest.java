package com.vortex.messaging;

import com.vortex.messaging.envelope.EnvelopeValidationException;
import com.vortex.messaging.envelope.EventType;
import com.vortex.messaging.kafka.KafkaTopics;
import org.junit.jupiter.api.Test;

import static com.vortex.messaging.MessagingFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class EventEnvelopeJsonCodecTest {
    @Test
    void wireRoundTripPreservesIdentifiersPayloadAndWireEventType() throws Exception {
        var event = equipmentChanged("belt-west");
        byte[] encoded = CODEC.serialize(event);
        assertEquals(event, CODEC.validate(encoded, "belt-west"));
        assertEquals("EquipmentStatusChanged", MAPPER.readTree(encoded).get("eventType").asText());
    }

    @Test
    void kafkaKeyMustEqualAggregateId() {
        byte[] encoded = CODEC.serialize(equipmentReport("belt-west"));
        assertThrows(EnvelopeValidationException.class, () -> CODEC.validate(encoded, "different-equipment"));
        assertThrows(EnvelopeValidationException.class, () -> CODEC.validate(encoded, null));
    }

    @Test
    void javaOutboxRoutesOnlyJavaOwnedFactsAndCommands() {
        assertEquals(KafkaTopics.COMMANDS, KafkaTopics.forJavaOutbox(EventType.EQUIPMENT_MOVEMENT_COMMAND));
        assertEquals(KafkaTopics.WORKFLOW, KafkaTopics.forJavaOutbox(EventType.BAG_CHECKED_IN));
        assertEquals(KafkaTopics.WORKFLOW, KafkaTopics.forJavaOutbox(EventType.EQUIPMENT_STATUS_CHANGED));
        assertThrows(IllegalArgumentException.class, () -> KafkaTopics.forJavaOutbox(EventType.SCANNER_OBSERVATION));
        assertThrows(IllegalArgumentException.class, () -> KafkaTopics.forJavaOutbox(EventType.EQUIPMENT_COMMAND_RESULT));
        assertThrows(IllegalArgumentException.class, () -> KafkaTopics.forJavaOutbox(EventType.EQUIPMENT_STATUS_REPORTED));
    }
}
