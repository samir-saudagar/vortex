package com.vortex.flight.api;

import com.vortex.flight.domain.Flight;
import java.time.LocalDate;
import java.util.List;

public interface FlightQuery {
    List<Flight> list(LocalDate date, String origin);
}
