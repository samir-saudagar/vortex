package com.vortex.bagstate.persistence;

import com.vortex.bagstate.api.BagQuery.HistoryItem;
import com.vortex.bagstate.api.BagSnapshot;
import com.vortex.bagstate.domain.BagState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcBagRepository {
    private final JdbcTemplate jdbc;
    public JdbcBagRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<BagSnapshot> find(UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM vortex.bags WHERE bag_id = ?" + (lock ? " FOR UPDATE" : ""),
                (rs, n) -> map(rs), id).stream().findFirst();
    }

    public List<HistoryItem> history(UUID id) {
        return jdbc.query("SELECT * FROM vortex.bag_history WHERE bag_id = ? ORDER BY history_sequence",
                (rs, n) -> new HistoryItem(rs.getObject("history_id", UUID.class), rs.getString("record_type"),
                        rs.getString("previous_state"), rs.getString("current_state"), rs.getString("reason_code"),
                        rs.getString("node_id"), rs.getTimestamp("occurred_at").toInstant(),
                        rs.getTimestamp("recorded_at").toInstant(), rs.getString("details"),
                        rs.getObject("state_version", Long.class)), id);
    }

    public boolean tagExists(String tag) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM vortex.bags WHERE bag_tag = ?)", Boolean.class, tag));
    }

    private BagSnapshot map(ResultSet rs) throws SQLException {
        return new BagSnapshot(rs.getObject("bag_id", UUID.class), rs.getString("bag_tag"), rs.getString("flight_id"),
                rs.getString("check_in_node_id"), rs.getString("current_node_id"),
                BagState.valueOf(rs.getString("current_state")), rs.getObject("current_route_id", UUID.class),
                rs.getObject("active_command_id", UUID.class), rs.getObject("correlation_id", UUID.class),
                rs.getLong("state_version"), rs.getString("failure_code"), rs.getString("failure_reason"),
                rs.getTimestamp("checked_in_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getObject("workflow_event_id", UUID.class), rs.getString("verified_departure_airport"),
                rs.getString("verified_destination_airport"), rs.getString("verified_flight_status"),
                rs.getTimestamp("verified_at") == null ? null : rs.getTimestamp("verified_at").toInstant());
    }
}
