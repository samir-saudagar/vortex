package com.vortex.messaging.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.messaging.envelope.EventEnvelopeJsonCodec;
import com.vortex.messaging.envelope.EventEnvelopeValidator;
import com.vortex.messaging.envelope.JsonEventEnvelopeValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import com.vortex.messaging.workflow.WorkflowDispatcher;
import com.vortex.messaging.workflow.SimulatorReportDispatcher;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerPausingBackOffHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.ListenerContainerPauseService;
import org.springframework.kafka.listener.ListenerContainerRegistry;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Map;

@Configuration
@EnableKafka
public class KafkaMessagingConfiguration {
    @Bean
    Clock messagingClock() {
        return Clock.systemUTC();
    }

    @Bean
    EventEnvelopeValidator eventEnvelopeValidator(ObjectMapper mapper) {
        return new JsonEventEnvelopeValidator(mapper);
    }

    @Bean
    EventEnvelopeJsonCodec eventEnvelopeJsonCodec(ObjectMapper mapper, EventEnvelopeValidator validator) {
        return new EventEnvelopeJsonCodec(mapper, validator);
    }

    @Bean(name = {"vortexTaskScheduler", "taskScheduler"})
    TaskScheduler vortexTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("vortex-scheduler-");
        return scheduler;
    }

    @Bean
    ListenerContainerPauseService listenerContainerPauseService(ListenerContainerRegistry registry,
                                                                 @org.springframework.beans.factory.annotation.Qualifier("vortexTaskScheduler") TaskScheduler scheduler) {
        return new ListenerContainerPauseService(registry, scheduler);
    }

    @Bean
    CommonErrorHandler kafkaErrorHandler(QuarantinePublisher quarantinePublisher, DataSource dataSource,
                                         ListenerContainerPauseService pauseService) {
        var retryPolicy = new ConsumerRetryBackOff(dataSource);
        DefaultErrorHandler handler = new DefaultErrorHandler(quarantinePublisher,
                retryPolicy, new ContainerPausingBackOffHandler(pauseService));
        handler.setClassifications(Map.of(), true);
        handler.setResetStateOnExceptionChange(false);
        handler.setRetryListeners(retryPolicy);
        handler.setAckAfterHandle(true);
        return handler;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, byte[]> kafkaListenerContainerFactory(
            ConsumerFactory<String, byte[]> consumerFactory, CommonErrorHandler kafkaErrorHandler,
            KafkaProperties kafkaProperties) {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.setConcurrency(1);
        factory.setAutoStartup(kafkaProperties.getListener().isAutoStartup());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setSyncCommits(true);
        factory.getContainerProperties().setPollTimeout(1_000L);
        return factory;
    }

    @Bean
    SmartInitializingSingleton validateEnabledConsumerHandlers(KafkaProperties properties,
                                                               WorkflowDispatcher workflow,
                                                               SimulatorReportDispatcher reports) {
        return () -> {
            if (properties.getListener().isAutoStartup()) {
                workflow.requireHandlers();
                reports.requireHandlers();
            }
        };
    }

    static boolean isDatabaseUnavailable(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof org.springframework.dao.DataAccessResourceFailureException
                    || current instanceof org.springframework.jdbc.CannotGetJdbcConnectionException) return true;
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && (state.startsWith("08") || state.equals("57P01") || state.equals("57P02"))) {
                    return true;
                }
            }
        }
        return false;
    }

    static final class MessageRetryBackOff implements BackOff {
        private static final long[] DELAYS = {1_000L, 5_000L, 15_000L};

        @Override
        public BackOffExecution start() {
            return new BackOffExecution() {
                private int index;

                @Override
                public long nextBackOff() {
                    return index < DELAYS.length ? DELAYS[index++] : STOP;
                }
            };
        }
    }

    /** One message retry budget per record, with a separate, inexhaustible dependency budget. */
    static final class ConsumerRetryBackOff implements BackOff, RetryListener {
        private final DataSource dataSource;
        private final ThreadLocal<Boolean> databaseFailure = new ThreadLocal<>();

        ConsumerRetryBackOff(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public void failedDelivery(org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record,
                                   Exception exception, int deliveryAttempt) {
            // Spring invokes this before nextBackOff, including when the exception type changes.
            databaseFailure.set(isDatabaseUnavailable(exception));
        }

        @Override
        public BackOffExecution start() {
            BackOffExecution message = new MessageRetryBackOff().start();
            BackOffExecution dependency = new DatabaseHealthBackOff(dataSource).start();
            return () -> {
                boolean unavailable = Boolean.TRUE.equals(databaseFailure.get());
                databaseFailure.remove();
                return (unavailable ? dependency : message).nextBackOff();
            };
        }
    }

    /** Keeps a valid record on its partition during PostgreSQL outages; delay probes database health. */
    static final class DatabaseHealthBackOff implements BackOff {
        private final DataSource dataSource;

        DatabaseHealthBackOff(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public BackOffExecution start() {
            return new BackOffExecution() {
                private int initialRetry;

                @Override
                public long nextBackOff() {
                    long[] initial = {1_000L, 5_000L, 15_000L};
                    if (initialRetry < initial.length) return initial[initialRetry++];
                    return databaseHealthy() ? 1_000L : 30_000L;
                }
            };
        }

        private boolean databaseHealthy() {
            try (var connection = dataSource.getConnection()) {
                return connection.isValid(1);
            } catch (SQLException exception) {
                return false;
            }
        }
    }
}
