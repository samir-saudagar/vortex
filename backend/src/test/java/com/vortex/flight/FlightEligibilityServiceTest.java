package com.vortex.flight;

import com.vortex.flight.api.FlightCatalog;
import com.vortex.flight.application.FlightEligibilityService;
import com.vortex.flight.domain.Flight;
import com.vortex.flight.domain.FlightEligibilityFailure;
import com.vortex.flight.domain.FlightStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlightEligibilityServiceTest {
    private static final Instant OPENS = Instant.parse("2030-01-14T00:00:00Z");
    private static final Instant CLOSES = Instant.parse("2030-01-15T12:00:00Z");
    private final Map<String, Flight> flights = Map.of(
            "scheduled", flight("scheduled", FlightStatus.SCHEDULED),
            "boarding", flight("boarding", FlightStatus.BOARDING),
            "cancelled", flight("cancelled", FlightStatus.CANCELLED));
    private final FlightEligibilityService service = new FlightEligibilityService(
            new FlightCatalog() {
                @Override
                public Optional<Flight> findById(String flightId) {
                    return Optional.ofNullable(flights.get(flightId));
                }
            });

    @Test
    void acceptsScheduledAndBoardingFlightsInsideTheOpenWindow() {
        assertTrue(service.verify("scheduled", "JFK", Instant.parse("2030-01-14T00:00:00Z")).eligible());
        assertTrue(service.verify("boarding", "JFK", Instant.parse("2030-01-15T11:59:59Z")).eligible());
    }

    @Test
    void returnsUnknownFlightAsAnEligibilityFailure() {
        assertEquals(FlightEligibilityFailure.UNKNOWN_FLIGHT,
                service.verify("missing", "JFK", Instant.parse("2030-01-15T10:00:00Z")).failure().orElseThrow());
    }

    @Test
    void rejectsWrongOriginIneligibleStatusAndClosedWindow() {
        assertEquals(FlightEligibilityFailure.WRONG_DEPARTURE_AIRPORT,
                service.verify("scheduled", "LHR", Instant.parse("2030-01-15T10:00:00Z")).failure().orElseThrow());
        assertEquals(FlightEligibilityFailure.FLIGHT_NOT_ELIGIBLE,
                service.verify("cancelled", "JFK", Instant.parse("2030-01-15T10:00:00Z")).failure().orElseThrow());
        assertEquals(FlightEligibilityFailure.CHECK_IN_NOT_OPEN,
                service.verify("scheduled", "JFK", Instant.parse("2030-01-13T23:59:59Z")).failure().orElseThrow());
        assertEquals(FlightEligibilityFailure.CHECK_IN_CLOSED,
                service.verify("scheduled", "JFK", CLOSES).failure().orElseThrow());
    }

    private static Flight flight(String id, FlightStatus status) {
        return new Flight(id, "VX", "204", LocalDate.parse("2030-01-15"), "JFK", "LHR", status,
                Optional.of(OPENS), CLOSES);
    }
}
