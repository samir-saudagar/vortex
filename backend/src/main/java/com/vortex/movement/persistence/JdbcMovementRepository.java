package com.vortex.movement.persistence;

import com.vortex.movement.api.MovementQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class JdbcMovementRepository implements MovementQuery {
    private final JdbcTemplate jdbc;
    public JdbcMovementRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<Map<String, Object>> commands(UUID id) {
        return jdbc.queryForList("SELECT command_id, route_id, route_step_no, attempt, equipment_id, from_node_id, to_node_id, status, issued_at, deadline_at, started_at, completed_at, failure_code, failure_reason FROM vortex.movement_commands WHERE bag_id = ? ORDER BY issued_at, command_id", id);
    }
    public List<Map<String, Object>> observations(UUID id) {
        return jdbc.queryForList("SELECT observation_id, route_id, command_id, route_step_no, node_id, scanner_id, decision, rejection_code, observed_at, received_at FROM vortex.equipment_observations WHERE bag_id = ? ORDER BY received_at, observation_id", id);
    }
    public List<Map<String, Object>> progress(UUID id) {
        return jdbc.queryForList("SELECT route_id, next_step_no, last_confirmed_node_id, active_command_id, status, observation_deadline_at, updated_at, row_version FROM vortex.route_progress WHERE bag_id = ? ORDER BY updated_at, route_id", id);
    }
}
