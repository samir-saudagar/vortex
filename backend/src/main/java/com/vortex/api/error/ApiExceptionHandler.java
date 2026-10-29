package com.vortex.api.error;

import com.vortex.messaging.workflow.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(WorkflowConflictException.class)
    ResponseEntity<Error> conflict(WorkflowConflictException e) { return error(409, e.code(), e.getMessage()); }
    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<Error> missing(ResourceNotFoundException e) { return error(404, e.code(), e.getMessage()); }
    @ExceptionHandler({IllegalArgumentException.class, MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Error> badRequest(Exception e) { return error(400, "INVALID_REQUEST", "Request fields, JSON body or headers are invalid"); }
    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<Error> unavailable(Exception e) { return error(503, "DATABASE_UNAVAILABLE", "Database is temporarily unavailable; retry later"); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Error> unexpected(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse transport) {
            return error(transport.getStatusCode().value(), "HTTP_REQUEST_ERROR", "Request method, media type or resource is not supported");
        }
        log.error("REST request failed ({})", e.getClass().getSimpleName());
        return error(500, "INTERNAL_ERROR", "Request could not be completed");
    }
    private ResponseEntity<Error> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(new Error(code, message, UUID.randomUUID()));
    }
    public record Error(String code, String message, UUID correlationId) { }
}
