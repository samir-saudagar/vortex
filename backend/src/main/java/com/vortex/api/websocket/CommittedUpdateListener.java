package com.vortex.api.websocket;

import com.vortex.messaging.envelope.EventEnvelopeJsonCodec;
import com.vortex.messaging.envelope.EventType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** Independent UI group: only outbox-published, committed facts reach the browser. */
@Component
public class CommittedUpdateListener {
    private final EventEnvelopeJsonCodec codec;
    private final SimpMessagingTemplate messaging;
    public CommittedUpdateListener(EventEnvelopeJsonCodec codec, SimpMessagingTemplate messaging) {
        this.codec = codec; this.messaging = messaging;
    }
    @KafkaListener(topics = "${vortex.kafka.topics.workflow-events}", groupId = "${vortex.kafka.groups.ui:vortex-ui}")
    public void receive(ConsumerRecord<String, byte[]> record) {
        var event = codec.validate(record.value(), record.key());
        String destination;
        if (event.eventType() == EventType.BAG_STATE_CHANGED) destination = "/topic/bags/" + event.aggregateId();
        else if (event.eventType() == EventType.EQUIPMENT_STATUS_CHANGED) destination = "/topic/equipment";
        else return;
        // Preserve the existing wire envelope (including wire eventType), without another DTO contract.
        messaging.convertAndSend(destination, new String(codec.serialize(event), java.nio.charset.StandardCharsets.UTF_8));
    }
}
