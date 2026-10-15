package com.vortex.messaging;

import com.vortex.messaging.outbox.*;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static com.vortex.messaging.MessagingFixtures.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxRelayTest {
    private final OutboxRepository repository = mock(OutboxRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, byte[]> kafka = mock(KafkaTemplate.class);
    private final TransactionTemplate transactions;

    OutboxRelayTest() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        transactions = new TransactionTemplate(manager);
    }

    private OutboxMessage message(int attempts) {
        return new OutboxMessage(42, "event-id", "vortex.workflow-events", "aggregate", new byte[]{1},
                "EQUIPMENT", "aggregate", 1, attempts);
    }

    private OutboxRelay relay(OutboxMessage message) {
        when(repository.claimNext(NOW, Duration.ofSeconds(30))).thenReturn(Optional.of(message), Optional.empty());
        return new OutboxRelay(repository, kafka, transactions, 30, 10, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void marksPublishedOnlyAfterBrokerAcknowledgement() {
        var message = message(1);
        var future = new CompletableFuture<org.springframework.kafka.support.SendResult<String, byte[]>>();
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            verify(repository, never()).markPublished(any(), any());
            future.complete(null);
            return future;
        });
        when(repository.markPublished(message, NOW)).thenReturn(true);
        relay(message).relayDueRows();
        var order = inOrder(repository, kafka);
        order.verify(repository).claimNext(NOW, Duration.ofSeconds(30));
        order.verify(kafka).send(any(ProducerRecord.class));
        order.verify(repository).markPublished(message, NOW);
        verify(repository, never()).markFailure(any(), any(), any(), anyBoolean(), anyString());
    }

    @ParameterizedTest
    @CsvSource({"1,1,false", "2,2,false", "6,32,false", "7,60,false", "20,60,false", "21,60,true"})
    void publishFailureUsesCappedBackoffAndExhaustsAfterTwentyRetries(int attempts, long delay, boolean exhausted) {
        var message = message(attempts);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(new TimeoutException("injected")));
        relay(message).relayDueRows();
        verify(repository).markFailure(eq(message), eq(NOW), eq(NOW.plusSeconds(delay)), eq(exhausted), anyString());
        verify(repository, never()).markPublished(any(), any());
    }

    @Test
    void failedRowsMakeHealthDownAndRemainVisible() {
        when(repository.counts()).thenReturn(new OutboxRepository.OutboxCounts(2, 1, 1));
        var health = new OutboxHealthIndicator(repository).health();
        assertEquals("DOWN", health.getStatus().getCode());
        assertEquals(1L, health.getDetails().get("failed"));
    }
}
