package com.vortex.workflow;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;

final class Phase4DatabaseCleanup {
    private Phase4DatabaseCleanup() { }
    static void bag(JdbcTemplate jdbc, TransactionTemplate transactions, UUID bag) {
        transactions.executeWithoutResult(s -> {
            jdbc.update("DELETE FROM vortex.outbox WHERE aggregate_type = 'BAG' AND aggregate_id = ?", bag.toString());
            jdbc.update("DELETE FROM vortex.bag_history WHERE bag_id = ?", bag);
            jdbc.update("DELETE FROM vortex.equipment_observations WHERE bag_id = ?", bag);
            jdbc.update("DELETE FROM vortex.route_progress WHERE bag_id = ?", bag);
            jdbc.update("DELETE FROM vortex.movement_commands WHERE bag_id = ?", bag);
            jdbc.update("DELETE FROM vortex.route_steps WHERE route_id IN (SELECT route_id FROM vortex.route_plans WHERE bag_id = ?)", bag);
            jdbc.update("DELETE FROM vortex.route_plans WHERE bag_id = ?", bag);
            jdbc.update("DELETE FROM vortex.workflow_retry_requests WHERE bag_id = ?", bag);
            jdbc.update("DELETE FROM vortex.checkin_requests WHERE bag_id = ?", bag);
            jdbc.update("DELETE FROM vortex.bags WHERE bag_id = ?", bag);
        });
    }
}
