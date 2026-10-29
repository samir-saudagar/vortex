package com.vortex.routing.persistence;

import com.vortex.routing.api.*;
import com.vortex.routing.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Repository
public class JdbcNetworkCatalog implements NetworkCatalog, EquipmentAvailability, EquipmentQuery {
    private final JdbcTemplate jdbc;
    public JdbcNetworkCatalog(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public NetworkGraph active() {
        var version = jdbc.queryForMap("SELECT * FROM vortex.network_versions WHERE status = 'ACTIVE'");
        String id = (String) version.get("network_version");
        return new NetworkGraph(id, (String) version.get("airport_code"), (String) version.get("check_in_node_id"),
                jdbc.query("SELECT * FROM vortex.network_nodes WHERE network_version = ? ORDER BY node_id", (rs, n) ->
                        new NetworkNode(rs.getString("node_id"), NetworkNodeType.valueOf(rs.getString("node_type")),
                                rs.getString("airport_destination_code"), rs.getString("equipment_scanner_id"), rs.getBoolean("enabled")), id),
                jdbc.query("SELECT * FROM vortex.network_edges WHERE network_version = ? ORDER BY edge_id", (rs, n) ->
                        new NetworkEdge(rs.getString("edge_id"), rs.getString("from_node_id"), rs.getString("to_node_id"),
                                rs.getString("equipment_id"), rs.getInt("cost"), rs.getObject("travel_time_ms", Integer.class), rs.getBoolean("enabled")), id),
                jdbc.query("SELECT * FROM vortex.equipment ORDER BY equipment_id", (rs, n) ->
                        new EquipmentSnapshot(rs.getString("equipment_id"), EquipmentType.valueOf(rs.getString("equipment_type")),
                                EquipmentStatus.valueOf(rs.getString("status")), rs.getBoolean("configured_enabled"))));
    }
    public boolean available(String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM vortex.equipment WHERE equipment_id = ? AND configured_enabled AND status = 'AVAILABLE')", Boolean.class, id));
    }
    public List<Item> equipment() { return query(null); }
    public Optional<Item> equipment(String id) { return query(id).stream().findFirst(); }
    private List<Item> query(String id) {
        return jdbc.query("SELECT * FROM vortex.equipment" + (id == null ? " ORDER BY equipment_id" : " WHERE equipment_id = ?"),
                (rs, n) -> new Item(rs.getString("equipment_id"), rs.getString("equipment_type"), rs.getString("status"),
                        rs.getTimestamp("last_status_at").toInstant()), id == null ? new Object[]{} : new Object[]{id});
    }
}
