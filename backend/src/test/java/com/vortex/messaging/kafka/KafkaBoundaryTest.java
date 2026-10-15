package com.vortex.messaging.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.vortex.messaging.envelope.EnvelopeValidationCode;
import com.vortex.messaging.envelope.EnvelopeValidationException;
import com.vortex.messaging.workflow.WorkflowDispatcher;
import com.vortex.messaging.workflow.SimulatorReportDispatcher;
import com.vortex.messaging.deduplication.ProcessingOutcome;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerContainerPauseService;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.BackOffExecution;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.vortex.messaging.MessagingFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KafkaBoundaryTest {
    @Test
    void alternatingMessageExceptionTypesShareExactlyFourAttempts() {
        var publisher = mock(QuarantinePublisher.class);
        var pauser = mock(ListenerContainerPauseService.class);
        var container = mock(MessageListenerContainer.class);
        var handler = (DefaultErrorHandler) new KafkaMessagingConfiguration()
                .kafkaErrorHandler(publisher, mock(DataSource.class), pauser);
        var record = new ConsumerRecord<>(KafkaTopics.REPORTS, 0, 42, "key", new byte[0]);
        for (int attempt = 1; attempt <= 4; attempt++) {
            Exception failure = attempt % 2 == 1 ? new IllegalArgumentException("poison")
                    : new IllegalStateException("poison");
            assertEquals(attempt == 4, handler.handleOne(failure, record, null, container));
        }
        var order = inOrder(pauser, publisher);
        order.verify(pauser).pause(container, Duration.ofSeconds(1));
        order.verify(pauser).pause(container, Duration.ofSeconds(5));
        order.verify(pauser).pause(container, Duration.ofSeconds(15));
        order.verify(publisher).accept(eq(record), any(IllegalStateException.class));
        verifyNoMoreInteractions(pauser, publisher);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void databaseOutagesNeitherExhaustNorResetTheMessageRetryBudget(boolean databaseFirst) throws Exception {
        var publisher = mock(QuarantinePublisher.class);
        var container = mock(MessageListenerContainer.class);
        var dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("outage", "08006"));
        var handler = (DefaultErrorHandler) new KafkaMessagingConfiguration().kafkaErrorHandler(
                publisher, dataSource, mock(ListenerContainerPauseService.class));
        var record = new ConsumerRecord<>(KafkaTopics.REPORTS, 0, 42, "key", new byte[0]);
        if (!databaseFirst) {
            for (int i = 0; i < 3; i++) {
                assertFalse(handler.handleOne(new IllegalStateException("poison"), record, null, container));
            }
        }
        for (int i = 0; i < 5; i++) {
            Exception outage = i % 2 == 0 ? new CannotGetJdbcConnectionException("outage")
                    : new RuntimeException(new SQLException("outage", "08006"));
            assertFalse(handler.handleOne(outage, record, null, container));
        }
        verifyNoInteractions(publisher);
        for (int i = databaseFirst ? 0 : 3; i < 4; i++) {
            assertEquals(i == 3, handler.handleOne(new IllegalArgumentException("poison"), record, null, container));
        }
        verify(publisher).accept(eq(record), any(IllegalArgumentException.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {1_600_000, 2_096_128})
    @SuppressWarnings("unchecked")
    void oversizedMalformedRecordsPreserveEveryByteWithinQuarantineCapacity(int size) throws Exception {
        KafkaTemplate<String, byte[]> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), any(byte[].class))).thenReturn(CompletableFuture.completedFuture(null));
        byte[] original = new byte[size];
        Arrays.fill(original, (byte) 0xff);
        var record = new ConsumerRecord<>(KafkaTopics.REPORTS, 0, 42, "key", original);
        new QuarantinePublisher(kafka, MAPPER, KafkaTopics.QUARANTINE).accept(record,
                new EnvelopeValidationException(EnvelopeValidationCode.ENVELOPE_TOO_LARGE, "oversized"));
        ArgumentCaptor<byte[]> value = ArgumentCaptor.forClass(byte[].class);
        verify(kafka).send(eq(KafkaTopics.QUARANTINE), eq("key"), value.capture());
        assertTrue(value.getValue().length > 2_097_152, "regression must exceed the old transport limit");
        assertTrue(value.getValue().length + 1024 < 33_554_432);
        JsonNode wrapper = MAPPER.readTree(value.getValue());
        assertArrayEquals(original, Base64.getDecoder().decode(wrapper.get("originalRawBase64").asText()));
        assertEquals("ENVELOPE_TOO_LARGE", wrapper.get("failureCode").asText());
        assertEquals(42, wrapper.get("sourceOffset").asLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void malformedLargeKeyIsNotTruncatedAndStillFitsQuarantineTransport() throws Exception {
        KafkaTemplate<String, byte[]> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), any(byte[].class))).thenReturn(CompletableFuture.completedFuture(null));
        String key = String.valueOf((char) 0).repeat(2_096_128);
        var record = new ConsumerRecord<>(KafkaTopics.REPORTS, 0, 42, key, new byte[]{(byte) 0xff});
        new QuarantinePublisher(kafka, MAPPER, KafkaTopics.QUARANTINE).accept(record, new IllegalArgumentException("poison"));
        ArgumentCaptor<byte[]> value = ArgumentCaptor.forClass(byte[].class);
        verify(kafka).send(eq(KafkaTopics.QUARANTINE), eq(key), value.capture());
        assertEquals(key, MAPPER.readTree(value.getValue()).get("originalKey").asText());
        assertTrue(value.getValue().length + key.getBytes(StandardCharsets.UTF_8).length + 1024 < 33_554_432);
    }

    @Test
    @SuppressWarnings("unchecked")
    void malformedTrailingJsonIsPreservedInFullRatherThanParsingOnlyItsPrefix() throws Exception {
        KafkaTemplate<String, byte[]> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), any(byte[].class))).thenReturn(CompletableFuture.completedFuture(null));
        byte[] original = "{} trailing diagnostic bytes".getBytes(StandardCharsets.UTF_8);
        new QuarantinePublisher(kafka, MAPPER, KafkaTopics.QUARANTINE).accept(
                new ConsumerRecord<>(KafkaTopics.REPORTS, 0, 42, "key", original), new IllegalArgumentException("poison"));
        ArgumentCaptor<byte[]> value = ArgumentCaptor.forClass(byte[].class);
        verify(kafka).send(eq(KafkaTopics.QUARANTINE), eq("key"), value.capture());
        assertArrayEquals(original, Base64.getDecoder().decode(MAPPER.readTree(value.getValue()).get("originalRawBase64").asText()));
    }

    @Test
    void messagePolicyHasExactlyThreeRetryDelays() {
        var policy = new KafkaMessagingConfiguration.MessageRetryBackOff().start();
        assertEquals(1_000, policy.nextBackOff());
        assertEquals(5_000, policy.nextBackOff());
        assertEquals(15_000, policy.nextBackOff());
        assertEquals(BackOffExecution.STOP, policy.nextBackOff());
    }

    @Test
    void databaseOutageUsesInitialRetriesThenHealthAwareDelayWithoutExhaustion() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(connection.isValid(1)).thenReturn(true);
        when(dataSource.getConnection()).thenThrow(new SQLException("injected outage", "08006")).thenReturn(connection);
        var policy = new KafkaMessagingConfiguration.DatabaseHealthBackOff(dataSource).start();
        assertEquals(1_000, policy.nextBackOff());
        assertEquals(5_000, policy.nextBackOff());
        assertEquals(15_000, policy.nextBackOff());
        verifyNoInteractions(dataSource);
        assertEquals(30_000, policy.nextBackOff());
        assertEquals(1_000, policy.nextBackOff());
        assertNotEquals(BackOffExecution.STOP, policy.nextBackOff());
    }

    @Test
    void onlyDependencyFailuresReceiveTheDatabaseOutagePolicy() {
        assertTrue(KafkaMessagingConfiguration.isDatabaseUnavailable(new CannotGetJdbcConnectionException("injected")));
        assertTrue(KafkaMessagingConfiguration.isDatabaseUnavailable(new RuntimeException(new SQLException("injected", "08006"))));
        assertFalse(KafkaMessagingConfiguration.isDatabaseUnavailable(new SQLException("constraint", "23505")));
        assertFalse(KafkaMessagingConfiguration.isDatabaseUnavailable(new IllegalArgumentException("poison")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void customFactoryHonorsDisabledStartupAndCommitsEachRecordSynchronously() {
        var properties = new KafkaProperties();
        properties.getListener().setAutoStartup(false);
        var factory = new KafkaMessagingConfiguration().kafkaListenerContainerFactory(
                mock(ConsumerFactory.class), mock(CommonErrorHandler.class), properties);
        assertFalse(factory.createContainer("diagnostic-topic").isAutoStartup());
        assertEquals(ContainerProperties.AckMode.RECORD, factory.getContainerProperties().getAckMode());
        assertTrue(factory.getContainerProperties().isSyncCommits());
    }

    @Test
    @SuppressWarnings("unchecked")
    void quarantinePreservesOriginalEnvelopeSourceAndFailureCause() throws Exception {
        KafkaTemplate<String, byte[]> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), any(byte[].class))).thenReturn(CompletableFuture.completedFuture(null));
        byte[] original = CODEC.serialize(equipmentReport("belt-west"));
        var record = new ConsumerRecord<>("diagnostic-reports", 2, 42, "belt-west", original);
        var failure = new ListenerExecutionFailedException("failed", "diagnostic-group",
                new EnvelopeValidationException(EnvelopeValidationCode.UNSUPPORTED_EVENT_VERSION, "unsupported"));
        new QuarantinePublisher(kafka, MAPPER, "diagnostic-quarantine").accept(record, failure);
        ArgumentCaptor<byte[]> value = ArgumentCaptor.forClass(byte[].class);
        verify(kafka).send(eq("diagnostic-quarantine"), eq("belt-west"), value.capture());
        JsonNode wrapper = MAPPER.readTree(value.getValue());
        assertEquals(MAPPER.readTree(original), wrapper.get("originalEnvelope"));
        assertArrayEquals(original, Base64.getDecoder().decode(wrapper.get("originalRawBase64").asText()));
        assertEquals("diagnostic-group", wrapper.get("consumerGroup").asText());
        assertEquals(2, wrapper.get("sourcePartition").asInt());
        assertEquals(42, wrapper.get("sourceOffset").asLong());
        assertEquals(4, wrapper.get("attempts").asInt());
        assertEquals("UNSUPPORTED_EVENT_VERSION", wrapper.get("failureCode").asText());
        assertEquals(EnvelopeValidationException.class.getName(), wrapper.get("failureClass").asText());
    }

    @Test
    @SuppressWarnings("unchecked")
    void invalidOriginalBytesArePreservedAndUnacknowledgedQuarantineThrows() throws Exception {
        KafkaTemplate<String, byte[]> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), any(byte[].class))).thenReturn(CompletableFuture.completedFuture(null));
        byte[] original = {(byte) 0xc3, (byte) 0x28};
        var record = new ConsumerRecord<>(KafkaTopics.REPORTS, 0, 0, "key", original);
        var publisher = new QuarantinePublisher(kafka, MAPPER, KafkaTopics.QUARANTINE);
        publisher.accept(record, new IllegalArgumentException("poison"));
        ArgumentCaptor<byte[]> value = ArgumentCaptor.forClass(byte[].class);
        verify(kafka).send(eq(KafkaTopics.QUARANTINE), eq("key"), value.capture());
        assertArrayEquals(original, Base64.getDecoder().decode(MAPPER.readTree(value.getValue()).get("originalRawBase64").asText()));
        when(kafka.send(anyString(), anyString(), any(byte[].class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker outage")));
        assertThrows(IllegalStateException.class, () -> publisher.accept(record, new RuntimeException("poison")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void databaseFailureCanNeverBeRecoveredToQuarantine() {
        KafkaTemplate<String, byte[]> kafka = mock(KafkaTemplate.class);
        var record = new ConsumerRecord<>(KafkaTopics.REPORTS, 0, 0, "key", new byte[0]);
        var publisher = new QuarantinePublisher(kafka, MAPPER, KafkaTopics.QUARANTINE);
        assertThrows(IllegalStateException.class, () -> publisher.accept(record,
                new CannotGetJdbcConnectionException("injected outage")));
        verifyNoInteractions(kafka);
    }

    @Test
    void absentPhaseFourHandlersCannotConsumeActionableEventsSilently() {
        var workflow = new WorkflowDispatcher(List.of());
        var reports = new SimulatorReportDispatcher(List.of());
        assertThrows(IllegalStateException.class, workflow::requireHandlers);
        assertThrows(IllegalStateException.class, reports::requireHandlers);
        assertThrows(IllegalStateException.class, () -> reports.dispatch(equipmentReport("belt-west")));
        assertEquals(ProcessingOutcome.PROCESSED, workflow.dispatch(equipmentChanged("belt-west")));
    }
}
