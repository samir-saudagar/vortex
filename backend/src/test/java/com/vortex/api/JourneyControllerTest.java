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

@WebMvcTest(JourneyController.class)
class JourneyControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean CheckInUseCase checkin;
    @MockitoBean BagQuery bags;
    @MockitoBean RouteQuery routes;
    @MockitoBean FlightQuery flights;
    @MockitoBean EquipmentQuery equipment;
    @MockitoBean MovementQuery movement;
    @MockitoBean RetryBagUseCase retry;

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
