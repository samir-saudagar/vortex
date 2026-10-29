package com.vortex.routing.persistence;

import com.vortex.routing.api.RouteQuery;
import com.vortex.routing.domain.RouteStep;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Repository
public class JdbcRouteRepository implements RouteQuery {
    private final JdbcTemplate jdbc;
    public JdbcRouteRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<StoredRoute> latest(UUID id) {
        return jdbc.query("SELECT * FROM vortex.route_plans WHERE bag_id = ? ORDER BY route_number DESC LIMIT 1",
                (rs, n) -> map(rs), id).stream().findFirst();
    }
    public Optional<StoredRoute> route(UUID id) {
        return jdbc.query("SELECT * FROM vortex.route_plans WHERE route_id = ?", (rs, n) -> map(rs), id).stream().findFirst();
    }
    public boolean finalMakeup(UUID routeId, String node, String scanner, String destination) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM vortex.route_plans r JOIN vortex.network_nodes n
                ON r.network_version = n.network_version AND r.destination_node_id = n.node_id
                WHERE r.route_id = ? AND n.node_type = 'MAKEUP' AND n.enabled
                AND n.node_id = ? AND n.equipment_scanner_id = ? AND n.airport_destination_code = ?)
                """, Boolean.class, routeId, node, scanner, destination));
    }
    private StoredRoute map(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("route_id", UUID.class);
        List<RouteStep> steps = jdbc.query("SELECT * FROM vortex.route_steps WHERE route_id = ? ORDER BY step_no",
                (row, n) -> new RouteStep(row.getInt("step_no"), row.getString("edge_id"), row.getString("from_node_id"),
                        row.getString("to_node_id"), row.getString("equipment_id"), row.getString("expected_scanner_id"),
                        row.getInt("edge_cost"), row.getObject("expected_travel_time_ms", Integer.class)), id);
        return new StoredRoute(id, rs.getObject("bag_id", UUID.class), rs.getInt("route_number"), rs.getString("status"),
                rs.getString("network_version"), rs.getString("source_node_id"), rs.getString("destination_node_id"),
                rs.getString("algorithm"), rs.getLong("total_cost"), steps, rs.getString("decision_summary"));
    }
}
