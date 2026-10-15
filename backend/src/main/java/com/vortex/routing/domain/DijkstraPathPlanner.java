package com.vortex.routing.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Dijkstra over positive costs; equal-cost paths use the lexicographically smallest edge-id sequence. */
public final class DijkstraPathPlanner {
    private static final String ALGORITHM = "DIJKSTRA_COST_V1";
    private final GraphValidator graphValidator;

    public DijkstraPathPlanner(GraphValidator graphValidator) {
        this.graphValidator = graphValidator;
    }

    public RouteDecision plan(NetworkGraph graph, String sourceNodeId, String destinationAirportCode) {
        GraphValidationResult validation = graphValidator.validate(graph);
        if (!validation.valid()) {
            return RouteDecision.failed(RouteFailure.INVALID_NETWORK, validation.issues(), List.of());
        }
        if (sourceNodeId == null || sourceNodeId.isBlank()) {
            return RouteDecision.failed(RouteFailure.UNKNOWN_SOURCE_NODE, List.of(), List.of());
        }
        if (destinationAirportCode == null || destinationAirportCode.isBlank()) {
            return RouteDecision.failed(RouteFailure.DESTINATION_NOT_CONFIGURED, List.of(), List.of());
        }

        Map<String, NetworkNode> nodes = new HashMap<>();
        graph.nodes().forEach(node -> nodes.put(node.nodeId(), node));
        Map<String, EquipmentSnapshot> equipment = new HashMap<>();
        graph.equipment().forEach(item -> equipment.put(item.equipmentId(), item));
        NetworkNode source = nodes.get(sourceNodeId);
        if (source == null || !source.enabled()) {
            return RouteDecision.failed(RouteFailure.UNKNOWN_SOURCE_NODE, List.of(), List.of());
        }
        List<NetworkNode> targets = graph.nodes().stream()
                .filter(node -> node.type() == NetworkNodeType.MAKEUP)
                .filter(node -> destinationAirportCode.equals(node.airportDestinationCode()))
                .sorted(Comparator.comparing(NetworkNode::nodeId))
                .toList();
        if (targets.isEmpty()) {
            return RouteDecision.failed(RouteFailure.DESTINATION_NOT_CONFIGURED, List.of(), List.of());
        }
        if (targets.size() > 1) {
            return RouteDecision.failed(RouteFailure.INVALID_NETWORK,
                    List.of(new GraphValidationIssue("AMBIGUOUS_MAKEUP_MAPPING", destinationAirportCode,
                            "A destination must map to exactly one makeup node")), List.of());
        }
        NetworkNode target = targets.getFirst();
        if (!target.enabled()) {
            return RouteDecision.failed(RouteFailure.NO_ROUTE, List.of(), List.of());
        }

        List<ExcludedEdge> exclusions = excludedEdges(graph, nodes, equipment);
        if (sourceNodeId.equals(target.nodeId())) {
            return RouteDecision.planned(new PlannedRoute(graph.networkVersion(), sourceNodeId, target.nodeId(),
                    ALGORITHM, 1, 0L, List.of()), exclusions);
        }

        Map<String, List<NetworkEdge>> outgoing = new HashMap<>();
        for (NetworkEdge edge : graph.edges()) {
            if (edge.enabled() && nodes.get(edge.fromNodeId()).enabled() && nodes.get(edge.toNodeId()).enabled()) {
                EquipmentSnapshot poweredBy = equipment.get(edge.equipmentId());
                if (poweredBy.configuredEnabled() && poweredBy.status() == EquipmentStatus.AVAILABLE) {
                    outgoing.computeIfAbsent(edge.fromNodeId(), ignored -> new ArrayList<>()).add(edge);
                }
            }
        }
        outgoing.values().forEach(edges -> edges.sort(Comparator.comparing(NetworkEdge::edgeId)));

        Comparator<PathLabel> labelOrder = Comparator.comparingLong(PathLabel::cost)
                .thenComparing(PathLabel::edgeIds, DijkstraPathPlanner::comparePaths)
                .thenComparing(PathLabel::nodeId);
        PriorityQueue<PathLabel> queue = new PriorityQueue<>(labelOrder);
        Map<String, PathLabel> best = new HashMap<>();
        PathLabel start = new PathLabel(sourceNodeId, 0L, List.of(), List.of());
        best.put(sourceNodeId, start);
        queue.add(start);

        while (!queue.isEmpty()) {
            PathLabel current = queue.remove();
            if (best.get(current.nodeId()) != current) {
                continue;
            }
            if (current.nodeId().equals(target.nodeId())) {
                List<RouteStep> steps = new ArrayList<>();
                for (int index = 0; index < current.edges().size(); index++) {
                    NetworkEdge edge = current.edges().get(index);
                    NetworkNode destination = nodes.get(edge.toNodeId());
                    steps.add(new RouteStep(index + 1, edge.edgeId(), edge.fromNodeId(), edge.toNodeId(),
                            edge.equipmentId(), destination.scannerEquipmentId(), edge.cost(), edge.travelTimeMs()));
                }
                return RouteDecision.planned(new PlannedRoute(graph.networkVersion(), sourceNodeId,
                        target.nodeId(), ALGORITHM, 1, current.cost(), steps), exclusions);
            }
            for (NetworkEdge edge : outgoing.getOrDefault(current.nodeId(), List.of())) {
                long candidateCost = current.cost() + edge.cost();
                if (candidateCost < current.cost()) {
                    throw new IllegalArgumentException("route cost overflow");
                }
                List<String> candidateEdgeIds = append(current.edgeIds(), edge.edgeId());
                List<NetworkEdge> candidateEdges = appendEdge(current.edges(), edge);
                PathLabel candidate = new PathLabel(edge.toNodeId(), candidateCost, candidateEdgeIds, candidateEdges);
                PathLabel previous = best.get(edge.toNodeId());
                if (previous == null || labelOrder.compare(candidate, previous) < 0) {
                    best.put(edge.toNodeId(), candidate);
                    queue.add(candidate);
                }
            }
        }
        return RouteDecision.failed(RouteFailure.NO_ROUTE, List.of(), exclusions);
    }

    private static List<ExcludedEdge> excludedEdges(NetworkGraph graph, Map<String, NetworkNode> nodes,
                                                    Map<String, EquipmentSnapshot> equipment) {
        List<ExcludedEdge> result = new ArrayList<>();
        for (NetworkEdge edge : graph.edges()) {
            String reason = null;
            if (!edge.enabled()) {
                reason = "EDGE_DISABLED";
            } else if (!nodes.get(edge.fromNodeId()).enabled()) {
                reason = "SOURCE_NODE_DISABLED";
            } else if (!nodes.get(edge.toNodeId()).enabled()) {
                reason = "DESTINATION_NODE_DISABLED";
            } else {
                EquipmentSnapshot item = equipment.get(edge.equipmentId());
                if (!item.configuredEnabled()) {
                    reason = "EQUIPMENT_DISABLED";
                } else if (item.status() != EquipmentStatus.AVAILABLE) {
                    reason = "EQUIPMENT_NOT_AVAILABLE";
                }
            }
            if (reason != null) {
                result.add(new ExcludedEdge(edge.edgeId(), reason));
            }
        }
        result.sort(Comparator.comparing(ExcludedEdge::edgeId).thenComparing(ExcludedEdge::reasonCode));
        return result;
    }

    private static List<String> append(List<String> values, String value) {
        List<String> next = new ArrayList<>(values);
        next.add(value);
        return List.copyOf(next);
    }

    private static List<NetworkEdge> appendEdge(List<NetworkEdge> values, NetworkEdge value) {
        List<NetworkEdge> next = new ArrayList<>(values);
        next.add(value);
        return List.copyOf(next);
    }

    private static int comparePaths(List<String> left, List<String> right) {
        int shared = Math.min(left.size(), right.size());
        for (int index = 0; index < shared; index++) {
            int compared = left.get(index).compareTo(right.get(index));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    private record PathLabel(String nodeId, long cost, List<String> edgeIds, List<NetworkEdge> edges) {
    }
}
