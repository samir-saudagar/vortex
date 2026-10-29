package com.vortex.routing.application;

import com.vortex.routing.api.EquipmentStatusUpdater;
import com.vortex.routing.domain.EquipmentStatus;
import com.vortex.messaging.envelope.*;
import com.vortex.messaging.outbox.OutboxWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;

@Service
public class PersistentEquipmentStatusUpdater implements EquipmentStatusUpdater {
    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final EventFactory events;
    public PersistentEquipmentStatusUpdater(JdbcTemplate jdbc, OutboxWriter outbox, EventFactory events) {
        this.jdbc = jdbc; this.outbox = outbox; this.events = events;
    }
    @Transactional
    public boolean update(String id, EquipmentStatus status, Instant reported, UUID report, UUID correlation, String reason) {
        var rows = jdbc.queryForList("SELECT status, last_status_at, last_report_id FROM vortex.equipment WHERE equipment_id = ? FOR UPDATE", id);
        if (rows.isEmpty()) return false;
        var old = rows.getFirst();
        // Seed timestamps are fixture dates; the first live report establishes the reporting clock.
        if (old.get("last_report_id") != null && (!reported.isAfter(((Timestamp) old.get("last_status_at")).toInstant())
                || report.equals(old.get("last_report_id")))) return false;
        jdbc.update("UPDATE vortex.equipment SET status = ?, last_status_at = ?, last_report_id = ?, status_reason = ? WHERE equipment_id = ?",
                status.name(), Timestamp.from(reported), report, reason, id);
        if (!status.name().equals(old.get("status"))) {
            outbox.append(new EventEnvelope(UUID.randomUUID(), EventType.EQUIPMENT_STATUS_CHANGED, 1, reported,
                    "route-planning", AggregateType.EQUIPMENT, id, correlation, report, events.payload("equipmentId", id,
                    "previousStatus", old.get("status"), "currentStatus", status, "changedAt", reported, "sourceReportId", report)));
        }
        return true;
    }
}
