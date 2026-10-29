package com.vortex.workflow;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "VORTEX_DB_INTEGRATION_TEST", matches = "true")
class Phase4MigrationIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Test void phase3DatabaseUpgradesWithoutLosingExistingRowsAndMigratesAgain() throws Exception {
        String parent = jdbc.queryForObject("SELECT current_database()", String.class);
        assertTrue(parent.startsWith("vortex_test_"));
        String database = "vortex_test_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String user = System.getenv().getOrDefault("POSTGRES_USER", "vortex");
        String password = System.getenv().getOrDefault("POSTGRES_PASSWORD", "vortex-local");
        try (Connection admin = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            String url = admin.getMetaData().getURL().replace("/" + parent, "/" + database);
            try (Statement statement = admin.createStatement()) { statement.execute("CREATE DATABASE " + database); }
            try {
                var configuration = Flyway.configure().dataSource(url, user, password).schemas("vortex").defaultSchema("vortex");
                configuration.target("4").load().migrate();
                UUID bag = UUID.randomUUID(), correlation = UUID.randomUUID(), history = UUID.randomUUID();
                try (Connection old = DriverManager.getConnection(url, user, password)) {
                    try (PreparedStatement statement = old.prepareStatement("INSERT INTO vortex.bags (bag_id, bag_tag, flight_id, check_in_node_id, current_node_id, current_state, correlation_id, checked_in_at, updated_at) VALUES (?, 'UPGRADE-BAG', 'UNKNOWN', 'checkin-main', 'checkin-main', 'CHECKED_IN', ?, now(), now())")) {
                        statement.setObject(1, bag); statement.setObject(2, correlation); statement.executeUpdate();
                    }
                    try (PreparedStatement statement = old.prepareStatement("INSERT INTO vortex.bag_history (history_id, bag_id, record_type, current_state, reason_code, occurred_at) VALUES (?, ?, 'STATE_TRANSITION', 'CHECKED_IN', 'CHECK_IN_ACCEPTED', now())")) {
                        statement.setObject(1, history); statement.setObject(2, bag); statement.executeUpdate();
                    }
                }
                var latest = Flyway.configure().dataSource(url, user, password).schemas("vortex").defaultSchema("vortex").load();
                assertEquals(1, latest.migrate().migrationsExecuted);
                assertEquals(0, latest.migrate().migrationsExecuted);
                try (Connection upgraded = DriverManager.getConnection(url, user, password); Statement statement = upgraded.createStatement();
                     ResultSet rows = statement.executeQuery("SELECT b.bag_tag, b.current_state, b.workflow_event_id, b.verified_at, h.history_sequence FROM vortex.bags b JOIN vortex.bag_history h USING (bag_id)")) {
                    assertTrue(rows.next()); assertEquals("UPGRADE-BAG", rows.getString(1)); assertEquals("CHECKED_IN", rows.getString(2));
                    assertNull(rows.getObject(3)); assertNull(rows.getObject(4)); assertTrue(rows.getLong(5) > 0);
                }
            } finally {
                try (Statement statement = admin.createStatement()) { statement.execute("DROP DATABASE " + database + " WITH (FORCE)"); }
            }
        }
    }
}
