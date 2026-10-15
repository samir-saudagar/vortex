package com.vortex.persistence;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.common.config.ConfigResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@EnabledIfEnvironmentVariable(named = "VORTEX_KAFKA_INTEGRATION_TEST", matches = "true")
class KafkaTopicPolicyIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String RETENTION_MS = "604800000";
    private static final Map<String, Integer> TOPICS = Map.of(
            "vortex.workflow-events", 3,
            "vortex.equipment-commands", 3,
            "vortex.simulator-reports", 3,
            "vortex.quarantine", 1);

    @Test
    void hostManagedClientCanListAndVerifyAllApprovedTopics() throws Exception {
        String bootstrapServers = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        Properties properties = new Properties();
        properties.put("bootstrap.servers", bootstrapServers);
        properties.put("request.timeout.ms", (int) TIMEOUT.toMillis());
        properties.put("default.api.timeout.ms", (int) TIMEOUT.toMillis());

        try (Admin admin = Admin.create(properties)) {
            var names = admin.listTopics().names().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertNotNull(names);
            assertEquals(true, names.containsAll(TOPICS.keySet()), "All configured topics must be visible from the host");

            var descriptions = admin.describeTopics(TOPICS.keySet())
                    .allTopicNames().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            var configs = admin.describeConfigs(TOPICS.keySet().stream()
                            .map(topic -> new ConfigResource(ConfigResource.Type.TOPIC, topic)).toList())
                    .all().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            for (var topic : TOPICS.entrySet()) {
                var description = descriptions.get(topic.getKey());
                assertNotNull(description, "Topic must be describable from the host: " + topic.getKey());
                assertEquals(topic.getValue(), description.partitions().size(), "Partition count for " + topic.getKey());
                description.partitions().forEach(partition ->
                        assertEquals(1, partition.replicas().size(), "Replication factor for " + topic.getKey()));

                Config topicConfig = configs.get(new ConfigResource(ConfigResource.Type.TOPIC, topic.getKey()));
                assertNotNull(topicConfig, "Topic config must be describable from the host: " + topic.getKey());
                ConfigEntry retention = topicConfig.get("retention.ms");
                assertNotNull(retention, "Explicit retention.ms must be set for " + topic.getKey());
                assertEquals(RETENTION_MS, retention.value(), "Retention for " + topic.getKey());
                String capacity = topic.getKey().equals("vortex.quarantine") ? "33554432" : "2097152";
                assertEquals(capacity, topicConfig.get("max.message.bytes").value(),
                        "Source capacity stays 2 MiB; quarantine preserves malformed bytes and keys");
            }
        }
    }
}
