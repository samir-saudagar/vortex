package com.vortex.routing.domain;

import java.util.List;

public record GraphValidationResult(List<GraphValidationIssue> issues) {
    public GraphValidationResult {
        issues = List.copyOf(issues);
    }

    public boolean valid() {
        return issues.isEmpty();
    }
}
