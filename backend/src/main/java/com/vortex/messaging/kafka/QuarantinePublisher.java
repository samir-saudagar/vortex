package com.vortex.messaging.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vortex.messaging.envelope.EnvelopeValidationException;
import com.vortex.messaging.envelope.JsonEventEnvelopeValidator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/** Publishes the original failed record and source coordinates; source acknowledgement follows broker ack. */
@Component
public class QuarantinePublisher implements ConsumerRecordRecoverer {
    private static final Logger log = LoggerFactory.getLogger(QuarantinePublisher.class);
    private final KafkaTemplate<String, byte[]> kafka;
    private final ObjectMapper mapper;
    private final String quarantineTopic;

    public QuarantinePublisher(KafkaTemplate<String, byte[]> kafka, ObjectMapper mapper,
                               @Value("${vortex.kafka.topics.quarantine:vortex.quarantine}") String quarantineTopic) {
        this.kafka = kafka;
        this.mapper = mapper.copy();
        this.quarantineTopic = quarantineTopic;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        if (KafkaMessagingConfiguration.isDatabaseUnavailable(exception)) {
            throw new IllegalStateException("PostgreSQL outage must not quarantine a valid event", exception);
        }
        try {
            byte[] original = record.value() instanceof byte[] bytes ? bytes : new byte[0];
            ObjectNode wrapper = mapper.createObjectNode();
            wrapper.put("sourceTopic", record.topic());
            wrapper.put("sourcePartition", record.partition());
            wrapper.put("sourceOffset", record.offset());
            String group = groupFor(record.topic());
            Throwable failure = exception;
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                failure = cause;
                if (cause instanceof ListenerExecutionFailedException listener && listener.getGroupId() != null) {
                    group = listener.getGroupId();
                }
                if (cause instanceof EnvelopeValidationException) break;
            }
            wrapper.put("consumerGroup", group);
            wrapper.put("attempts", 4);
            wrapper.put("failedAt", Instant.now().toString());
            wrapper.put("failureCode", failure instanceof EnvelopeValidationException validation
                    ? validation.code().name() : "CONSUMER_PROCESSING_FAILED");
            wrapper.put("failureClass", failure.getClass().getName());
            wrapper.put("safeReason", "The record could not be processed after four attempts");
            if (record.key() != null) wrapper.put("originalKey", record.key().toString());
            // Keep the exact bytes even when parsing succeeds; redrive must not lose diagnostic data.
            wrapper.put("originalRawBase64", Base64.getEncoder().encodeToString(original));
            try {
                if (original.length > JsonEventEnvelopeValidator.MAX_ENVELOPE_BYTES) {
                    throw new IOException("oversized original retained as raw bytes");
                }
                JsonNode originalJson = mapper.reader()
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(original);
                if (originalJson == null) throw new IOException("empty JSON");
                wrapper.set("originalEnvelope", originalJson);
            } catch (IOException invalidJson) {
                wrapper.putNull("originalEnvelope");
            }
            byte[] value = mapper.writeValueAsBytes(wrapper);
            kafka.send(quarantineTopic, record.key() == null ? null : record.key().toString(), value)
                    .get(10, TimeUnit.SECONDS);
            log.warn("Quarantined record from {} partition {} offset {} after four attempts",
                    record.topic(), record.partition(), record.offset());
        } catch (Exception publishFailure) {
            if (publishFailure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("quarantine publish did not receive broker acknowledgement", publishFailure);
        }
    }

    private static String groupFor(String topic) {
        return switch (topic) {
            case "vortex.workflow-events" -> "vortex-workflow";
            case "vortex.simulator-reports" -> "vortex-java-simulator-reports";
            default -> "unknown";
        };
    }
}
