package com.vortex.routing.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Structural graph checks required before a network can be planned. */
public final class GraphValidator {
    public GraphValidationResult validate(NetworkGraph graph) {
        List<GraphValidationIssue> issues = new ArrayList<>();
        if (graph == null) {
            return new GraphValidationResult(List.of(new GraphValidationIssue("GRAPH_REQUIRED", "", "Network graph is required")));
        }
        requireText(graph.networkVersion(), "NETWORK_VERSION_REQUIRED", "networkVersion", issues);
        requireText(graph.airportCode(), "AIRPORT_CODE_REQUIRED", "airportCode", issues);
        requireText(graph.checkInNodeId(), "CHECKIN_NODE_REQUIRED", "checkInNodeId", issues);

        Map<String, NetworkNode> nodes = new HashMap<>();
        for (NetworkNode node : graph.nodes()) {
            if (node == null || blank(node.nodeId())) {
                issues.add(new GraphValidationIssue("NODE_ID_REQUIRED", "", "Every node needs an identifier"));
                continue;
            }
            if (nodes.putIfAbsent(node.nodeId(), node) != null) {
                issues.add(issue("DUPLICATE_NODE", node.nodeId(), "Node identifiers must be unique"));
            }
            if (node.type() == null) {
                issues.add(issue("NODE_TYPE_REQUIRED", node.nodeId(), "Node type is required"));
            }
            if (node.type() == NetworkNodeType.MAKEUP) {
                requireText(node.airportDestinationCode(), "MAKEUP_DESTINATION_REQUIRED", node.nodeId(), issues);
                requireText(node.scannerEquipmentId(), "MAKEUP_SCANNER_REQUIRED", node.nodeId(), issues);
            }
        }

        Map<String, EquipmentSnapshot> equipment = new HashMap<>();
        for (EquipmentSnapshot item : graph.equipment()) {
            if (item == null || blank(item.equipmentId())) {
                issues.add(new GraphValidationIssue("EQUIPMENT_ID_REQUIRED", "", "Every equipment item needs an identifier"));
                continue;
            }
            if (equipment.putIfAbsent(item.equipmentId(), item) != null) {
                issues.add(issue("DUPLICATE_EQUIPMENT", item.equipmentId(), "Equipment identifiers must be unique"));
            }
            if (item.type() == null || item.status() == null) {
                issues.add(issue("EQUIPMENT_STATE_REQUIRED", item.equipmentId(), "Equipment type and status are required"));
            }
        }

        NetworkNode checkIn = nodes.get(graph.checkInNodeId());
        if (checkIn == null) {
            issues.add(issue("CHECKIN_NODE_MISSING", graph.checkInNodeId(), "Configured check-in node is absent from the graph"));
        } else if (checkIn.type() != NetworkNodeType.CHECK_IN) {
            issues.add(issue("CHECKIN_NODE_TYPE_INVALID", graph.checkInNodeId(), "Configured check-in node must have type CHECK_IN"));
        }

        Map<String, List<String>> makeupByDestination = new HashMap<>();
        for (NetworkNode node : graph.nodes()) {
            if (node == null || blank(node.nodeId())) {
                continue;
            }
            if (node.scannerEquipmentId() != null && !node.scannerEquipmentId().isBlank()) {
                EquipmentSnapshot scanner = equipment.get(node.scannerEquipmentId());
                if (scanner == null) {
                    issues.add(issue("SCANNER_EQUIPMENT_MISSING", node.nodeId(), "Node scanner equipment is not configured"));
                } else if (scanner.type() != EquipmentType.SCANNER) {
                    issues.add(issue("SCANNER_EQUIPMENT_TYPE_INVALID", node.nodeId(), "Node scanner reference must identify SCANNER equipment"));
                }
            }
            if (node.type() == NetworkNodeType.MAKEUP && !blank(node.airportDestinationCode())) {
                makeupByDestination.computeIfAbsent(node.airportDestinationCode(), ignored -> new ArrayList<>()).add(node.nodeId());
            }
        }
        makeupByDestination.forEach((destination, nodeIds) -> {
            if (nodeIds.size() > 1) {
                issues.add(issue("AMBIGUOUS_MAKEUP_MAPPING", destination, "A destination must map to exactly one makeup node"));
            }
        });

        Set<String> edgeIds = new HashSet<>();
        for (NetworkEdge edge : graph.edges()) {
            if (edge == null || blank(edge.edgeId())) {
                issues.add(new GraphValidationIssue("EDGE_ID_REQUIRED", "", "Every edge needs an identifier"));
                continue;
            }
            if (!edgeIds.add(edge.edgeId())) {
                issues.add(issue("DUPLICATE_EDGE", edge.edgeId(), "Edge identifiers must be unique within a graph"));
            }
            NetworkNode source = nodes.get(edge.fromNodeId());
            NetworkNode destination = nodes.get(edge.toNodeId());
            if (source == null) {
                issues.add(issue("EDGE_SOURCE_MISSING", edge.edgeId(), "Edge source node is not configured"));
            }
            if (destination == null) {
                issues.add(issue("EDGE_DESTINATION_MISSING", edge.edgeId(), "Edge destination node is not configured"));
            }
            EquipmentSnapshot poweredBy = equipment.get(edge.equipmentId());
            if (poweredBy == null) {
                issues.add(issue("EDGE_EQUIPMENT_MISSING", edge.edgeId(), "Edge equipment is not configured"));
            }
            if (edge.cost() <= 0) {
                issues.add(issue("EDGE_COST_INVALID", edge.edgeId(), "Edge cost must be positive"));
            }
            if (edge.travelTimeMs() != null && edge.travelTimeMs() <= 0) {
                issues.add(issue("EDGE_TRAVEL_TIME_INVALID", edge.edgeId(), "Edge travel time must be positive when configured"));
            }
            if (edge.enabled() && destination != null &&
                    (destination.scannerEquipmentId() == null || destination.scannerEquipmentId().isBlank())) {
                issues.add(issue("ROUTE_DESTINATION_SCANNER_REQUIRED", edge.edgeId(), "Every enabled edge destination must have a scanner"));
            }
        }

        issues.sort(Comparator.comparing(GraphValidationIssue::code)
                .thenComparing(GraphValidationIssue::referenceId)
                .thenComparing(GraphValidationIssue::safeReason));
        return new GraphValidationResult(issues);
    }

    private static void requireText(String value, String code, String referenceId, List<GraphValidationIssue> issues) {
        if (blank(value)) {
            issues.add(issue(code, referenceId, code.replace('_', ' ').toLowerCase()));
        }
    }

    private static GraphValidationIssue issue(String code, String referenceId, String reason) {
        return new GraphValidationIssue(code, referenceId == null ? "" : referenceId, reason);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
