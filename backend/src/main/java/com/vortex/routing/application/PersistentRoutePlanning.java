package com.vortex.routing.application;

import com.vortex.bagstate.api.*;
import com.vortex.bagstate.domain.*;
import com.vortex.routing.api.*;
import com.vortex.routing.domain.*;
import com.vortex.messaging.envelope.*;
import com.vortex.messaging.outbox.OutboxWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;

@Service
@Transactional
public class PersistentRoutePlanning implements RoutePlanning, RoutePlanLifecycle {
    private final JdbcTemplate jdbc;
    private final BagLifecycle bags;
    private final NetworkCatalog networks;
    private final EventFactory events;
    private final OutboxWriter outbox;
    private final Clock clock;
    private final RoutePlanner planner = new DeterministicRoutePlanner(new DijkstraPathPlanner(new GraphValidator()));
    public PersistentRoutePlanning(JdbcTemplate jdbc, BagLifecycle bags, NetworkCatalog networks,
                                     EventFactory events, OutboxWriter outbox, Clock clock) {
        this.jdbc = jdbc; this.bags = bags; this.networks = networks; this.events = events; this.outbox = outbox; this.clock = clock;
    }
    public boolean plan(UUID id, UUID source, UUID correlation) {
        BagSnapshot bag = bags.lock(id).orElse(null);
        if (bag == null) return false;
        if (!bag.expects(source, correlation) || !Set.of(BagState.FLIGHT_VERIFIED, BagState.ROUTING_BLOCKED, BagState.MOVEMENT_FAILED).contains(bag.state())) {
            bags.record(id, "WORKFLOW_RESULT", "STALE_ROUTE_WORKFLOW", source, clock.instant(), null);
            return false;
        }
        NetworkGraph graph = networks.active();
        RouteDecision decision = planner.plan(graph, bag.confirmedNodeId(), bag.verifiedDestinationAirport());
        var planned = decision.route().orElse(null);
        boolean available = planned != null && !planned.steps().isEmpty();
        String destination = graph.nodes().stream().filter(n -> n.type() == NetworkNodeType.MAKEUP
                && Objects.equals(n.airportDestinationCode(), bag.verifiedDestinationAirport())).map(NetworkNode::nodeId).findFirst()
                .orElse("unmapped-" + bag.verifiedDestinationAirport());
        String failure = decision.failure().map(Enum::name).orElse("CONFIRMED_DESTINATION_SCAN_REQUIRED");
        UUID routeId = UUID.randomUUID();
        int number = jdbc.queryForObject("SELECT COALESCE(MAX(route_number), 0) + 1 FROM vortex.route_plans WHERE bag_id = ?", Integer.class, id);
        if (bag.routeId() != null) supersede(bag.routeId());
        jdbc.update("""
                INSERT INTO vortex.route_plans (route_id, bag_id, route_number, status, network_version, source_node_id,
                  destination_node_id, algorithm, algorithm_version, total_cost, decision_summary, planned_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'DIJKSTRA_COST_V1', 1, ?, ?::jsonb, ?)
                """, routeId, id, number, available ? "PLANNED" : "BLOCKED", graph.networkVersion(), bag.confirmedNodeId(),
                destination, available ? planned.totalCost() : 0, events.payload("excludedEdges", decision.excludedEdges(),
                        "validationIssues", decision.validationIssues(), "failureCode", available ? null : failure,
                        "tieBreak", "LEXICOGRAPHIC_EDGE_ID_SEQUENCE").toString(), Timestamp.from(clock.instant()));
        if (available) for (RouteStep step : planned.steps()) {
            jdbc.update("INSERT INTO vortex.route_steps (route_id, step_no, edge_id, from_node_id, to_node_id, equipment_id, expected_scanner_id, edge_cost, expected_travel_time_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    routeId, step.stepNo(), step.edgeId(), step.fromNodeId(), step.toNodeId(), step.equipmentId(),
                    step.expectedScannerId(), step.edgeCost(), step.expectedTravelTimeMs());
        }
        bags.selection(id, routeId, null);
        EventEnvelope fact = available
                ? events.bag(EventType.ROUTE_PLANNED, id, correlation, source, events.payload("bagId", id,
                        "routeId", routeId, "fromNodeId", bag.confirmedNodeId(), "destinationNodeId", destination,
                        "networkVersion", graph.networkVersion(), "algorithm", planned.algorithm(), "totalCost", planned.totalCost(), "stepCount", planned.steps().size()))
                : events.bag(EventType.ROUTING_BLOCKED, id, correlation, source, events.payload("bagId", id,
                        "fromNodeId", bag.confirmedNodeId(), "destinationNodeId", destination, "networkVersion", graph.networkVersion(),
                        "failureCode", failure, "safeReason", "No observable route is available"));
        outbox.append(fact);
        BagTransitionTrigger trigger = switch (bag.state()) {
            case ROUTING_BLOCKED -> available ? BagTransitionTrigger.ROUTE_RETRY_AVAILABLE : BagTransitionTrigger.ROUTE_RETRY_UNAVAILABLE;
            case MOVEMENT_FAILED -> available ? BagTransitionTrigger.MOVEMENT_RETRY_ROUTE_AVAILABLE : BagTransitionTrigger.MOVEMENT_RETRY_NO_ROUTE;
            default -> available ? BagTransitionTrigger.ROUTE_AVAILABLE : BagTransitionTrigger.ROUTE_UNAVAILABLE;
        };
        bags.transition(id, trigger, source, clock.instant(), available ? null : failure, available ? null : "No observable route is available", null);
        bags.workflow(id, available ? fact.eventId() : null);
        return true;
    }
    public void activate(UUID id) { jdbc.update("UPDATE vortex.route_plans SET status = 'ACTIVE' WHERE route_id = ? AND status = 'PLANNED'", id); }
    public void complete(UUID id) { jdbc.update("UPDATE vortex.route_plans SET status = 'COMPLETE' WHERE route_id = ? AND status = 'ACTIVE'", id); }
    public void supersede(UUID id) { jdbc.update("UPDATE vortex.route_plans SET status = 'SUPERSEDED' WHERE route_id = ? AND status <> 'COMPLETE'", id); }
}
