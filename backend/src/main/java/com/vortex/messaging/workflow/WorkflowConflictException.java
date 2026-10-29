package com.vortex.messaging.workflow;

public class WorkflowConflictException extends RuntimeException {
    private final String code;
    public WorkflowConflictException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
