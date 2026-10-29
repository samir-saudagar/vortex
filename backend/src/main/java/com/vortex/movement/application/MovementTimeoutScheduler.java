package com.vortex.movement.application;

import com.vortex.movement.api.MovementTimeouts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "vortex.movement.sweeper-enabled", havingValue = "true", matchIfMissing = true)
public class MovementTimeoutScheduler {
    private static final Logger log = LoggerFactory.getLogger(MovementTimeoutScheduler.class);
    private final JdbcTemplate jdbc;
    private final MovementTimeouts timeouts;
    private final Clock clock;
    public MovementTimeoutScheduler(JdbcTemplate jdbc, MovementTimeouts timeouts, Clock clock) {
        this.jdbc = jdbc; this.timeouts = timeouts; this.clock = clock;
    }
    @Scheduled(fixedDelayString = "${vortex.movement.sweep-interval-ms:1000}")
    public void sweep() {
        try {
            var ids = jdbc.query("""
                    SELECT bag_id FROM vortex.movement_commands WHERE status IN ('ISSUED', 'ACCEPTED', 'STARTED') AND deadline_at <= ?
                    UNION SELECT bag_id FROM vortex.route_progress WHERE status = 'WAITING_FOR_OBSERVATION' AND observation_deadline_at <= ?
                    """, (rs, n) -> rs.getObject(1, UUID.class), Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
            for (UUID id : ids) timeouts.expire(id);
        } catch (RuntimeException e) { log.warn("Movement deadline sweep deferred: {}", e.getClass().getSimpleName()); }
    }
}
