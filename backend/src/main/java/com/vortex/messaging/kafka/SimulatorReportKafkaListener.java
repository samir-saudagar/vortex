package com.vortex.messaging.kafka;

import com.vortex.messaging.deduplication.TransactionalEventConsumer;
import com.vortex.messaging.envelope.EventEnvelopeJsonCodec;
import com.vortex.messaging.workflow.SimulatorReportDispatcher;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Component
public class SimulatorReportKafkaListener {
    private final EventEnvelopeJsonCodec codec;
    private final TransactionalEventConsumer consumer;
    private final SimulatorReportDispatcher dispatcher;
    private final String consumerName;

    public SimulatorReportKafkaListener(EventEnvelopeJsonCodec codec, TransactionalEventConsumer consumer,
                                        SimulatorReportDispatcher dispatcher,
                                        @Value("${vortex.kafka.groups.simulator-reports}") String consumerName) {
        this.codec = codec;
        this.consumer = consumer;
        this.dispatcher = dispatcher;
        this.consumerName = consumerName;
    }

    @KafkaListener(topics = "${vortex.kafka.topics.simulator-reports}", groupId = "${vortex.kafka.groups.simulator-reports}")
    public void receive(ConsumerRecord<String, byte[]> record) {
        var event = codec.validate(record.value(), record.key());
        consumer.consume(consumerName, event, () -> dispatcher.dispatch(event));
    }
}
