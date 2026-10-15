package com.vortex.routing.domain;

import java.util.List;
import java.util.Optional;

public record RouteDecision(
        Optional<PlannedRoute> route,
        Optional<RouteFailure> failure,
        List<GraphValidationIssue> validationIssues,
        List<ExcludedEdge> excludedEdges) {

    public RouteDecision {
        route = route == null ? Optional.empty() : route;
        failure = failure == null ? Optional.empty() : failure;
        validationIssues = List.copyOf(validationIssues);
        excludedEdges = List.copyOf(excludedEdges);
        if (route.isPresent() == failure.isPresent()) {
            throw new IllegalArgumentException("route decisions contain either a route or a failure");
        }
    }

    public static RouteDecision planned(PlannedRoute route, List<ExcludedEdge> excludedEdges) {
        return new RouteDecision(Optional.of(route), Optional.empty(), List.of(), excludedEdges);
    }

    public static RouteDecision failed(RouteFailure failure, List<GraphValidationIssue> issues,
                                       List<ExcludedEdge> excludedEdges) {
        return new RouteDecision(Optional.empty(), Optional.of(failure), issues, excludedEdges);
    }
}
