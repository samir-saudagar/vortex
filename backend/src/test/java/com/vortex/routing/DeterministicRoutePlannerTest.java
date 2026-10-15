package com.vortex.routing;

import com.vortex.routing.domain.DijkstraPathPlanner;
import com.vortex.routing.domain.EquipmentSnapshot;
import com.vortex.routing.domain.EquipmentStatus;
import com.vortex.routing.domain.EquipmentType;
import com.vortex.routing.domain.GraphValidator;
import com.vortex.routing.domain.NetworkEdge;
import com.vortex.routing.domain.NetworkGraph;
import com.vortex.routing.domain.NetworkNode;
import com.vortex.routing.domain.NetworkNodeType;
import com.vortex.routing.domain.RouteFailure;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicRoutePlannerTest {
    private final DijkstraPathPlanner planner = new DijkstraPathPlanner(new GraphValidator());

    @Test
    void selectsLowestCostPathAndReturnsScannerCheckpointSnapshots() {
        var decision = planner.plan(graph(), "checkin-main", "LHR");

        assertTrue(decision.route().isPresent());
        assertEquals(4L, decision.route().orElseThrow().totalCost());
        assertEquals(List.of("edge-a", "edge-a2"), decision.route().orElseThrow().steps().stream()
                .map(step -> step.edgeId()).toList());
        assertEquals("scanner-makeup", decision.route().orElseThrow().steps().getLast().expectedScannerId());
    }

    @Test
    void equalCostPathsChooseTheLexicographicallySmallestEdgeIdSequence() {
        NetworkGraph graph = graph();
        for (int index = 0; index < 5; index++) {
            var route = planner.plan(graph, "checkin-main", "LHR").route().orElseThrow();
            assertEquals(List.of("edge-a", "edge-a2"), route.steps().stream().map(step -> step.edgeId()).toList());
        }
    }

    @Test
    void excludesDisabledEdgesAndUnavailableOrDisabledEquipmentWithReasons() {
        NetworkGraph graph = graph();
        List<NetworkEdge> edges = new ArrayList<>(graph.edges());
        edges.add(new NetworkEdge("edge-disabled", "checkin-main", "west", "belt-a", 1, 10, false));
        edges.add(new NetworkEdge("edge-blocked", "checkin-main", "west", "belt-blocked", 1, 10, true));
        edges.add(new NetworkEdge("edge-equipment-disabled", "checkin-main", "west", "belt-disabled", 1, 10, true));
        NetworkGraph withExclusions = new NetworkGraph(graph.networkVersion(), graph.airportCode(), graph.checkInNodeId(),
                graph.nodes(), edges, append(graph.equipment(),
                new EquipmentSnapshot("belt-blocked", EquipmentType.CONVEYOR, EquipmentStatus.BLOCKED, true),
                new EquipmentSnapshot("belt-disabled", EquipmentType.CONVEYOR, EquipmentStatus.AVAILABLE, false)));

        var decision = planner.plan(withExclusions, "checkin-main", "LHR");
        assertEquals(List.of("edge-blocked:EQUIPMENT_NOT_AVAILABLE", "edge-disabled:EDGE_DISABLED",
                        "edge-equipment-disabled:EQUIPMENT_DISABLED"),
                decision.excludedEdges().stream().map(edge -> edge.edgeId() + ":" + edge.reasonCode()).toList());
        assertEquals(List.of("edge-a", "edge-a2"), decision.route().orElseThrow().steps().stream()
                .map(step -> step.edgeId()).toList());
    }

    @Test
    void reportsNoRouteAndMissingDestination() {
        NetworkGraph original = graph();
        NetworkGraph disabled = new NetworkGraph(original.networkVersion(), original.airportCode(), original.checkInNodeId(),
                original.nodes(), original.edges().stream().map(edge -> edge.fromNodeId().equals("checkin-main")
                        ? new NetworkEdge(edge.edgeId(), edge.fromNodeId(), edge.toNodeId(), edge.equipmentId(),
                        edge.cost(), edge.travelTimeMs(), false) : edge).toList(), original.equipment());
        assertEquals(RouteFailure.NO_ROUTE, planner.plan(disabled, "checkin-main", "LHR").failure().orElseThrow());
        assertEquals(RouteFailure.DESTINATION_NOT_CONFIGURED,
                planner.plan(original, "checkin-main", "SFO").failure().orElseThrow());
    }

    @Test
    void validatorRejectsUnscannedRoutedDestinationAndAmbiguousMakeupMapping() {
        NetworkGraph valid = graph();
        List<NetworkNode> nodes = new ArrayList<>(valid.nodes());
        nodes.set(1, new NetworkNode("west", NetworkNodeType.JUNCTION, null, null, true));
        nodes.add(new NetworkNode("makeup-2", NetworkNodeType.MAKEUP, "LHR", "scanner-makeup", true));
        var result = new GraphValidator().validate(new NetworkGraph(valid.networkVersion(), valid.airportCode(),
                valid.checkInNodeId(), nodes, valid.edges(), valid.equipment()));
        assertFalse(result.valid());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("ROUTE_DESTINATION_SCANNER_REQUIRED")));
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("AMBIGUOUS_MAKEUP_MAPPING")));
    }

    @Test
    void validatorReportsMissingReferencesAndNonPositiveCosts() {
        NetworkGraph valid = graph();
        List<NetworkNode> nodes = new ArrayList<>(valid.nodes());
        nodes.set(1, new NetworkNode("west", NetworkNodeType.JUNCTION, null, "scanner-missing", true));
        List<NetworkEdge> edges = new ArrayList<>(valid.edges());
        edges.add(new NetworkEdge("broken-edge", "unknown-source", "unknown-target", "missing-belt", 0, 0, true));

        var result = new GraphValidator().validate(new NetworkGraph(valid.networkVersion(), valid.airportCode(),
                valid.checkInNodeId(), nodes, edges, valid.equipment()));
        var codes = result.issues().stream().map(issue -> issue.code()).collect(java.util.stream.Collectors.toSet());
        assertTrue(codes.contains("SCANNER_EQUIPMENT_MISSING"));
        assertTrue(codes.contains("EDGE_SOURCE_MISSING"));
        assertTrue(codes.contains("EDGE_DESTINATION_MISSING"));
        assertTrue(codes.contains("EDGE_EQUIPMENT_MISSING"));
        assertTrue(codes.contains("EDGE_COST_INVALID"));
        assertTrue(codes.contains("EDGE_TRAVEL_TIME_INVALID"));
    }

    private static NetworkGraph graph() {
        List<NetworkNode> nodes = List.of(
                new NetworkNode("checkin-main", NetworkNodeType.CHECK_IN, null, null, true),
                new NetworkNode("west", NetworkNodeType.JUNCTION, null, "scanner-west", true),
                new NetworkNode("east", NetworkNodeType.JUNCTION, null, "scanner-east", true),
                new NetworkNode("makeup-lhr", NetworkNodeType.MAKEUP, "LHR", "scanner-makeup", true));
        List<NetworkEdge> edges = List.of(
                new NetworkEdge("edge-a", "checkin-main", "west", "belt-a", 2, 100, true),
                new NetworkEdge("edge-a2", "west", "makeup-lhr", "belt-a2", 2, 100, true),
                new NetworkEdge("edge-b", "checkin-main", "east", "belt-b", 2, 100, true),
                new NetworkEdge("edge-b2", "east", "makeup-lhr", "belt-b2", 2, 100, true),
                new NetworkEdge("edge-direct", "checkin-main", "makeup-lhr", "belt-direct", 7, 100, true));
        List<EquipmentSnapshot> equipment = List.of(
                new EquipmentSnapshot("belt-a", EquipmentType.CONVEYOR, EquipmentStatus.AVAILABLE, true),
                new EquipmentSnapshot("belt-a2", EquipmentType.CONVEYOR, EquipmentStatus.AVAILABLE, true),
                new EquipmentSnapshot("belt-b", EquipmentType.CONVEYOR, EquipmentStatus.AVAILABLE, true),
                new EquipmentSnapshot("belt-b2", EquipmentType.CONVEYOR, EquipmentStatus.AVAILABLE, true),
                new EquipmentSnapshot("belt-direct", EquipmentType.CONVEYOR, EquipmentStatus.AVAILABLE, true),
                new EquipmentSnapshot("scanner-west", EquipmentType.SCANNER, EquipmentStatus.AVAILABLE, true),
                new EquipmentSnapshot("scanner-east", EquipmentType.SCANNER, EquipmentStatus.AVAILABLE, true),
                new EquipmentSnapshot("scanner-makeup", EquipmentType.SCANNER, EquipmentStatus.AVAILABLE, true));
        return new NetworkGraph("demo-v1", "JFK", "checkin-main", nodes, edges, equipment);
    }

    private static List<EquipmentSnapshot> append(List<EquipmentSnapshot> base, EquipmentSnapshot... values) {
        List<EquipmentSnapshot> items = new ArrayList<>(base);
        items.addAll(List.of(values));
        return items;
    }
}
