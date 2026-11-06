package com.vortex.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;

import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Crash a separate backend JVM at each durable checkpoint; resume with real Kafka/Python. */
@EnabledIfEnvironmentVariable(named = "VORTEX_PHASE6_TEST", matches = "true")
class Phase6ReliabilityIntegrationTest {
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER) Path directory;
    @ParameterizedTest
    @ValueSource(strings = {"CHECKED_IN", "FLIGHT_VERIFIED", "ROUTE_PLANNED", "MOVEMENT_PENDING", "IN_TRANSIT",
            "WAITING_FOR_OBSERVATION", "INTERMEDIATE_CONFIRMED", "UNCERTAIN_COMMAND"})
    void abruptBackendRestartResumesDurableJourneyWithoutFabricatingMovement(String checkpoint) throws Exception {
        String prefix = "vortex.phase6-test." + UUID.randomUUID();
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")))) {
            admin.createTopics(List.of("workflow-events", "equipment-commands", "simulator-reports", "quarantine").stream()
                    .map(name -> new NewTopic(prefix + "." + name, name.equals("quarantine") ? 1 : 3, (short) 1)
                            .configs(Map.of("retention.ms", "604800000", "max.message.bytes", name.equals("quarantine") ? "33554432" : "2097152")))
                    .toList()).all().get(30, TimeUnit.SECONDS);
        }
        runWorker("prepare", checkpoint, prefix);
        var mapper = new ObjectMapper();
        var before = mapper.readTree(directory.resolve("checkpoint.json").toFile());
        assertEquals(checkpoint.equals("INTERMEDIATE_CONFIRMED") ? "junction-west" : "checkin-main", before.path("node").asText());
        assertNotEquals("AT_MAKEUP", before.path("state").asText());
        runWorker("resume", checkpoint, prefix);
        var after = mapper.readTree(directory.resolve("verified.json").toFile());
        assertEquals(before.path("bagId"), after.path("bagId"));
        assertEquals("AT_MAKEUP", after.path("state").asText());
        assertEquals("makeup-lhr", after.path("node").asText());
    }
    void runWorker(String mode, String checkpoint, String prefix) throws Exception {
        Path log = directory.resolve(mode + ".log");
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m", "-cp", Objects.requireNonNull(System.getProperty("vortex.test.classpath")),
                Phase6RestartWorker.class.getName(), mode, checkpoint, directory.toString(), prefix);
        builder.environment().put("VORTEX_COMMAND_TOPIC", prefix + ".equipment-commands");
        builder.environment().put("VORTEX_REPORT_TOPIC", prefix + ".simulator-reports");
        builder.environment().put("VORTEX_QUARANTINE_TOPIC", prefix + ".quarantine");
        builder.environment().put("VORTEX_SIMULATOR_GROUP", prefix + "-python");
        Process child = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(120, TimeUnit.SECONDS), "Worker timed out; " + log);
            assertEquals(0, child.exitValue(), () -> {
                try { return Files.readString(log) + "\n" + Files.readString(directory.resolve("python.log")); }
                catch (java.io.IOException e) { return log.toString() + ": " + e; }
            });
        } finally {
            if (child.isAlive()) {
                child.descendants().forEach(ProcessHandle::destroy);
                child.destroy();
                if (!child.waitFor(10, TimeUnit.SECONDS)) child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
            System.out.println("Phase 6 " + checkpoint + " " + mode + " evidence: " + log);
        }
    }
}
