package com.vortex.routing.domain;

import java.util.List;

/** Immutable input snapshot for validation and route planning. */
public record NetworkGraph(
        String networkVersion,
        String airportCode,
        String checkInNodeId,
        List<NetworkNode> nodes,
        List<NetworkEdge> edges,
        List<EquipmentSnapshot> equipment) {

    public NetworkGraph {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        equipment = equipment == null ? List.of() : List.copyOf(equipment);
    }
}
