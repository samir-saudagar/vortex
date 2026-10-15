package com.vortex.diagnostics;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.AppInfoParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class KafkaBrokerSmokeTest {
    private static final String TOPIC = "vortex.phase0.smoke";
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(30);

    @Test
    void managedKafkaClientCanCreateProduceAndConsume() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("VORTEX_KAFKA_SMOKE_TEST")),
                "Set VORTEX_KAFKA_SMOKE_TEST=true to run against the local broker");

        String bootstrapServers = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        String payload = "vortex-kafka-smoke-" + UUID.randomUUID();
        String key = "phase0-smoke";

        Admin admin = Admin.create(adminProperties(bootstrapServers));
        try {
            try {
                admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1)))
                        .all()
                        .get(OPERATION_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            } catch (ExecutionException exception) {
                if (!(exception.getCause() instanceof TopicExistsException)) {
                    throw exception;
                }
            }

            Map<String, org.apache.kafka.clients.admin.TopicDescription> topics = admin
                    .describeTopics(List.of(TOPIC))
                    .allTopicNames()
                    .get(OPERATION_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            var description = topics.get(TOPIC);
            assertNotNull(description, "Smoke topic must exist");
            assertEquals(1, description.partitions().size(), "Smoke topic must have one partition");
            assertEquals(1, description.partitions().get(0).replicas().size(),
                    "Single-node smoke topic must have one replica");
        } finally {
            admin.close(Duration.ofSeconds(2));
        }

        Properties producerProperties = new Properties();
        producerProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties)) {
            producer.send(new ProducerRecord<>(TOPIC, key, payload))
                    .get(OPERATION_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        Properties consumerProperties = new Properties();
        consumerProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, "vortex-smoke-" + UUID.randomUUID());
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProperties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        String receivedPayload = null;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties)) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.nanoTime() + OPERATION_TIMEOUT.toNanos();
            while (receivedPayload == null && System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(250));
                for (ConsumerRecord<String, String> record : records) {
                    if (payload.equals(record.value())) {
                        assertEquals(key, record.key(), "Consumed record key must match");
                        receivedPayload = record.value();
                        break;
                    }
                }
            }
        }

        assertEquals(payload, receivedPayload, "Consumer must receive the published payload");
        System.out.printf("Kafka smoke passed: broker image=apache/kafka:4.3.1, java-client=%s, topic=%s, payload=%s%n",
                AppInfoParser.getVersion(), TOPIC, receivedPayload);
    }

    private static Properties adminProperties(String bootstrapServers) {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", bootstrapServers);
        properties.put("request.timeout.ms", (int) OPERATION_TIMEOUT.toMillis());
        properties.put("default.api.timeout.ms", (int) OPERATION_TIMEOUT.toMillis());
        return properties;
    }
}
