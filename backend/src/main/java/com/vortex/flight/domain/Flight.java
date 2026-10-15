package com.vortex.flight.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

public record Flight(
        String flightId,
        String carrierCode,
        String flightNumber,
        LocalDate serviceDate,
        String departureAirport,
        String destinationAirport,
        FlightStatus status,
        Optional<Instant> checkInOpensAt,
        Instant checkInClosesAt) {

    public Flight {
        flightId = required(flightId, "flightId");
        carrierCode = required(carrierCode, "carrierCode");
        flightNumber = required(flightNumber, "flightNumber");
        Objects.requireNonNull(serviceDate, "serviceDate");
        departureAirport = required(departureAirport, "departureAirport");
        destinationAirport = required(destinationAirport, "destinationAirport");
        Objects.requireNonNull(status, "status");
        checkInOpensAt = Objects.requireNonNull(checkInOpensAt, "checkInOpensAt");
        Objects.requireNonNull(checkInClosesAt, "checkInClosesAt");
        if (checkInOpensAt.isPresent() && !checkInOpensAt.get().isBefore(checkInClosesAt)) {
            throw new IllegalArgumentException("check-in opening time must precede closing time");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
