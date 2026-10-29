package com.vortex.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "VORTEX_DB_INTEGRATION_TEST", matches = "true")
class PersistenceFoundationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @Test
    void migrationsAreRepeatableOnAnAlreadyMigratedDatabase() {
        flyway.migrate();
        assertEquals(0, flyway.info().pending().length);
        assertEquals(5, jdbc.queryForObject(
                "select count(*) from vortex.flyway_schema_history where success = true and version is not null",
                Integer.class));
    }

    @Test
    void deterministicCatalogSeedHasExpectedFixtures() {
        assertEquals(3, jdbc.queryForObject("select count(*) from vortex.flights", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from vortex.flights where status in ('SCHEDULED', 'BOARDING') and departure_airport = 'JFK'",
                Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from vortex.flights where status in ('SCHEDULED', 'BOARDING') " +
                        "and departure_airport = 'JFK' and check_in_opens_at <= current_timestamp " +
                        "and check_in_closes_at > current_timestamp",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from vortex.flights where status = 'CANCELLED'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from vortex.network_versions where status = 'ACTIVE' and airport_code = 'JFK'",
                Integer.class));
    }

    @Test
    void makeupDestinationsAreUnambiguousWithinTheActiveNetwork() {
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from vortex.network_nodes n " +
                        "join vortex.network_versions v using (network_version) " +
                        "where v.status = 'ACTIVE' and n.node_type = 'MAKEUP'",
                Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from (" +
                        "select n.airport_destination_code from vortex.network_nodes n " +
                        "join vortex.network_versions v using (network_version) " +
                        "where v.status = 'ACTIVE' and n.node_type = 'MAKEUP' " +
                        "group by n.airport_destination_code having count(*) <> 1) ambiguous",
                Integer.class));
    }

    @Test
    void everyEnabledEdgeDestinationHasAConfiguredScanner() {
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from vortex.network_edges e " +
                        "join vortex.network_nodes n on n.network_version = e.network_version and n.node_id = e.to_node_id " +
                        "left join vortex.equipment scanner on scanner.equipment_id = n.equipment_scanner_id " +
                        "where e.enabled and (n.equipment_scanner_id is null or scanner.equipment_type <> 'SCANNER')",
                Integer.class));
    }

    @Test
    void allTopologyEquipmentReferencesResolveToCompatibleEquipment() {
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from vortex.network_edges e " +
                        "left join vortex.equipment equipment on equipment.equipment_id = e.equipment_id " +
                        "where equipment.equipment_id is null or equipment.equipment_type not in ('CONVEYOR', 'DIVERTER')",
                Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from vortex.network_nodes n " +
                        "left join vortex.equipment scanner on scanner.equipment_id = n.equipment_scanner_id " +
                        "where n.equipment_scanner_id is not null and " +
                        "(scanner.equipment_id is null or scanner.equipment_type <> 'SCANNER')",
                Integer.class));
        assertTrue(jdbc.queryForObject(
                "select exists(select 1 from vortex.equipment where equipment_id = 'belt-blocked' and status = 'BLOCKED')",
                Boolean.class));
    }
}
