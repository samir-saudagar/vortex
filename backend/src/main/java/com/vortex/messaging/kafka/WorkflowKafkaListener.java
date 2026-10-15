package com.vortex.messaging.kafka;

import com.vortex.messaging.deduplication.TransactionalEventConsumer;
import com.vortex.messaging.envelope.EventEnvelopeJsonCodec;
import com.vortex.messaging.workflow.WorkflowDispatcher;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Component
public class WorkflowKafkaListener {
    private final EventEnvelopeJsonCodec codec;
    private final TransactionalEventConsumer consumer;
    private final WorkflowDispatcher dispatcher;
    private final String consumerName;

    public WorkflowKafkaListener(EventEnvelopeJsonCodec codec, TransactionalEventConsumer consumer,
                                 WorkflowDispatcher dispatcher,
                                 @Value("${vortex.kafka.groups.workflow}") String consumerName) {
        this.codec = codec;
        this.consumer = consumer;
        this.dispatcher = dispatcher;
        this.consumerName = consumerName;
    }

    @KafkaListener(topics = "${vortex.kafka.topics.workflow-events}", groupId = "${vortex.kafka.groups.workflow}")
    public void receive(ConsumerRecord<String, byte[]> record) {
        var event = codec.validate(record.value(), record.key());
        consumer.consume(consumerName, event, () -> dispatcher.dispatch(event));
    }
}
