package com.vortex.api;

import com.vortex.api.rest.JourneyController;
import com.vortex.bagstate.api.BagQuery;
import com.vortex.checkin.api.CheckInUseCase;
import com.vortex.flight.api.FlightQuery;
import com.vortex.messaging.workflow.*;
import com.vortex.movement.api.MovementQuery;
import com.vortex.routing.api.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = JourneyController.class, properties = "VORTEX_CORS_ORIGIN=http://localhost:5173")
class JourneyControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean CheckInUseCase checkin;
    @MockitoBean BagQuery bags;
    @MockitoBean RouteQuery routes;
    @MockitoBean FlightQuery flights;
    @MockitoBean EquipmentQuery equipment;
    @MockitoBean MovementQuery movement;
    @MockitoBean RetryBagUseCase retry;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"http://localhost:5173", "http://127.0.0.1:5173"})
    void localBrowserOriginReachesCheckIn(String origin) throws Exception {
        UUID id = UUID.randomUUID();
        when(checkin.checkIn(anyString(), any())).thenReturn(new CheckInUseCase.Accepted(id, "ORIGIN-TEST", "FL-204-2030-01-15",
                UUID.randomUUID(), "CHECKED_IN", "/api/bags/" + id, "/api/bags/" + id + "/history"));
        var result = mvc.perform(post("/api/bags/check-in").header("Origin", origin)
                .header("Idempotency-Key", "origin-test").contentType("application/json")
                .content("{\"bagTag\":\"ORIGIN-TEST\",\"flightId\":\"FL-204-2030-01-15\"}")).andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(202, result.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(origin, result.getResponse().getHeader("Access-Control-Allow-Origin"));
        org.mockito.Mockito.verify(checkin).checkIn(eq("origin-test"), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"http://localhost:5173", "http://127.0.0.1:5173"})
    void localOriginsCanPreflightReadAndReachTheGuardedRecoveryHandler(String origin) throws Exception {
        mvc.perform(options("/api/bags/check-in").header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,idempotency-key"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", origin));
        when(bags.summary()).thenReturn(new BagQuery.Summary(0, 0, 0, 0));
        mvc.perform(get("/api/bags/summary").header("Origin", origin))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", origin));
        UUID id = UUID.randomUUID();
        when(retry.retry(eq(id), anyString())).thenThrow(new WorkflowConflictException("BAG_NOT_RETRYABLE", "Bag is not retryable"));
        mvc.perform(post("/api/bags/" + id + "/retry").header("Origin", origin)
                .header("Idempotency-Key", "origin-retry").contentType("application/json").content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BAG_NOT_RETRYABLE"))
                .andExpect(header().string("Access-Control-Allow-Origin", origin));
        org.mockito.Mockito.verify(retry).retry(id, "origin-retry");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"http://untrusted.example", "http://127.0.0.1:5174", "https://localhost:5173", "null"})
    void unrelatedOriginsCannotReadWriteOrPreflight(String origin) throws Exception {
        mvc.perform(get("/api/bags/summary").header("Origin", origin)).andExpect(status().isForbidden());
        mvc.perform(post("/api/bags/check-in").header("Origin", origin).header("Idempotency-Key", "blocked")
                .contentType("application/json").content("{\"bagTag\":\"T\",\"flightId\":\"F\"}"))
                .andExpect(status().isForbidden()).andExpect(content().string("Invalid CORS request"));
        mvc.perform(options("/api/bags/check-in").header("Origin", origin).header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(checkin, retry, bags);
    }

    @Test void localOriginsStillCannotPreflightUnsupportedMethodsOrHeaders() throws Exception {
        mvc.perform(options("/api/bags/check-in").header("Origin", "http://127.0.0.1:5173")
                .header("Access-Control-Request-Method", "DELETE")).andExpect(status().isForbidden());
        mvc.perform(options("/api/bags/check-in").header("Origin", "http://127.0.0.1:5173")
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "X-Unapproved"))
                .andExpect(status().isForbidden());
    }

    @Test void listValidatesFiltersAndPagination() throws Exception {
        for (String query : List.of("state=CANCELLED", "page=-1", "size=0", "size=101", "page=oops", "search=" + "x".repeat(129))) {
            mvc.perform(get("/api/bags?" + query)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        when(bags.list("tag", "AT_MAKEUP", 2, 25)).thenReturn(new BagQuery.Page(List.of(), 0, 2, 25));
        mvc.perform(get("/api/bags?search=tag&state=AT_MAKEUP&page=2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.page").value(2));
        when(bags.summary()).thenReturn(new BagQuery.Summary(10, 5, 3, 2));
        mvc.perform(get("/api/bags/summary")).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(5));
    }
    @Test void missingHeaderMalformedJsonAndInvalidPathReturnSafe400() throws Exception {
        mvc.perform(post("/api/bags/check-in").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST")).andExpect(jsonPath("$.correlationId").isString());
        mvc.perform(post("/api/bags/check-in").header("Idempotency-Key", "key").contentType("application/json").content("{"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/bags/not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/flights?date=invalid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/flights?origin=invalid")).andExpect(status().isBadRequest());
    }
    @Test void retryRequiresEmptyObjectAndIdempotencyHeader() throws Exception {
        UUID id = UUID.randomUUID();
        for (String body : List.of("[]", "null", "{\"force\":true}")) {
            mvc.perform(post("/api/bags/{id}/retry", id).header("Idempotency-Key", "key").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/bags/{id}/retry", id).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
    }
    @Test void missingResourcesReturn404() throws Exception {
        when(bags.find(any())).thenReturn(Optional.empty());
        for (String suffix : List.of("", "/route", "/history", "/movement")) {
            mvc.perform(get("/api/bags/" + UUID.randomUUID() + suffix)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BAG_NOT_FOUND"));
        }
        when(equipment.equipment("unknown")).thenReturn(Optional.empty());
        mvc.perform(get("/api/equipment/unknown")).andExpect(status().isNotFound());
    }
    @Test void idempotencyConflictReturnsSafe409() throws Exception {
        when(checkin.checkIn(anyString(), any())).thenThrow(new WorkflowConflictException("IDEMPOTENCY_KEY_CONFLICT", "Idempotency key was used for another request"));
        mvc.perform(post("/api/bags/check-in").header("Idempotency-Key", "key").contentType("application/json").content("{\"bagTag\":\"tag\",\"flightId\":\"flight\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
    }
    @Test void databaseOutageReturns503WithoutDatabaseDetails() throws Exception {
        when(bags.find(any())).thenThrow(new CannotGetJdbcConnectionException("jdbc:secret database failure"));
        mvc.perform(get("/api/bags/" + UUID.randomUUID())).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("jdbc:"))));
    }
    @Test void unexpectedFailureDoesNotExposeSqlOrStack() throws Exception {
        when(bags.find(any())).thenThrow(new IllegalStateException("SQL SELECT secret_column"));
        mvc.perform(get("/api/bags/" + UUID.randomUUID())).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Request could not be completed"));
    }
}
