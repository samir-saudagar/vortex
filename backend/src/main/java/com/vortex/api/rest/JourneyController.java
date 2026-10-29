package com.vortex.api.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.vortex.bagstate.api.*;
import com.vortex.checkin.api.CheckInUseCase;
import com.vortex.flight.api.FlightQuery;
import com.vortex.messaging.workflow.*;
import com.vortex.movement.api.MovementQuery;
import com.vortex.routing.api.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class JourneyController {
    private final CheckInUseCase checkin;
    private final BagQuery bags;
    private final RouteQuery routes;
    private final FlightQuery flights;
    private final EquipmentQuery equipment;
    private final MovementQuery movement;
    private final RetryBagUseCase retry;
    public JourneyController(CheckInUseCase checkin, BagQuery bags, RouteQuery routes, FlightQuery flights,
                              EquipmentQuery equipment, MovementQuery movement, RetryBagUseCase retry) {
        this.checkin = checkin; this.bags = bags; this.routes = routes; this.flights = flights;
        this.equipment = equipment; this.movement = movement; this.retry = retry;
    }
    @PostMapping("/bags/check-in")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @ApiResponses({@ApiResponse(responseCode = "202", description = "Durably accepted; idempotent replay returns the original response"),
            @ApiResponse(responseCode = "400", description = "Invalid request or missing idempotency key"),
            @ApiResponse(responseCode = "409", description = "Duplicate bag tag or idempotency conflict"),
            @ApiResponse(responseCode = "503", description = "Database unavailable")})
    public ResponseEntity<CheckInUseCase.Accepted> checkin(@RequestHeader("Idempotency-Key") String key, @RequestBody CheckInUseCase.Request body) {
        return ResponseEntity.accepted().body(checkin.checkIn(key, body));
    }
    @GetMapping("/bags/{id}")
    public BagView bag(@PathVariable UUID id) { return BagView.of(requireBag(id)); }
    @GetMapping("/bags/{id}/history")
    public Map<String, Object> history(@PathVariable UUID id) {
        requireBag(id); return Map.of("bagId", id, "items", bags.history(id));
    }
    @GetMapping("/bags/{id}/route")
    public RouteView route(@PathVariable UUID id) {
        requireBag(id);
        var r = routes.latest(id).orElseThrow(() -> new ResourceNotFoundException("ROUTE_NOT_FOUND", "No route has been planned yet"));
        return new RouteView(r.routeId(), r.status(), r.networkVersion(), r.sourceNodeId(), r.destinationNodeId(), r.algorithm(), r.totalCost(),
                r.steps().stream().map(s -> new StepView(s.stepNo(), s.fromNodeId(), s.toNodeId(), s.equipmentId(), s.expectedScannerId(), s.edgeCost())).toList(), r.decisionSummary());
    }
    @GetMapping("/bags/{id}/movement")
    public Map<String, Object> movement(@PathVariable UUID id) {
        requireBag(id); return Map.of("bagId", id, "commands", movement.commands(id), "observations", movement.observations(id), "progress", movement.progress(id));
    }
    @PostMapping("/bags/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @ApiResponses({@ApiResponse(responseCode = "202", description = "Asynchronous retry durably accepted"),
            @ApiResponse(responseCode = "400", description = "Invalid request or missing idempotency key"),
            @ApiResponse(responseCode = "404", description = "Unknown bag"),
            @ApiResponse(responseCode = "409", description = "Bag not retryable, retry pending, or idempotency conflict"),
            @ApiResponse(responseCode = "503", description = "Database unavailable")})
    public ResponseEntity<RetryBagUseCase.Accepted> retry(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode body) {
        if (!body.isObject() || !body.isEmpty()) throw new IllegalArgumentException("Retry body must be an empty JSON object");
        return ResponseEntity.accepted().body(retry.retry(id, key));
    }
    @GetMapping("/flights")
    public Map<String, Object> flights(@RequestParam(required = false) LocalDate date, @RequestParam(required = false) String origin) {
        if (origin != null && !origin.matches("[A-Z]{3}")) throw new IllegalArgumentException("origin must be a three-letter airport code");
        return Map.of("items", flights.list(date, origin).stream().map(f -> new FlightView(f.flightId(), f.carrierCode(), f.flightNumber(),
                f.serviceDate(), f.departureAirport(), f.destinationAirport(), f.status().name(), f.checkInClosesAt())).toList());
    }
    @GetMapping("/equipment")
    public Map<String, Object> equipment() { return Map.of("items", equipment.equipment()); }
    @GetMapping("/equipment/{id}")
    public EquipmentQuery.Item equipment(@PathVariable String id) {
        return equipment.equipment(id).orElseThrow(() -> new ResourceNotFoundException("EQUIPMENT_NOT_FOUND", "Equipment was not found"));
    }
    private BagSnapshot requireBag(UUID id) { return bags.find(id).orElseThrow(() -> new ResourceNotFoundException("BAG_NOT_FOUND", "Bag was not found")); }
    public record BagView(UUID bagId, String bagTag, String flightId, String state, String confirmedNodeId, UUID routeId,
                          String failureCode, String failureReason, Instant updatedAt, long stateVersion) {
        static BagView of(BagSnapshot b) { return new BagView(b.bagId(), b.bagTag(), b.flightId(), b.state().name(), b.confirmedNodeId(), b.routeId(), b.failureCode(), b.failureReason(), b.updatedAt(), b.stateVersion()); }
    }
    public record RouteView(UUID routeId, String status, String networkVersion, String sourceNodeId, String destinationNodeId,
                            String algorithm, long totalCost, List<StepView> steps, String decisionSummary) { }
    public record StepView(int stepNo, String fromNodeId, String toNodeId, String equipmentId, String expectedScannerId, int cost) { }
    public record FlightView(String flightId, String carrierCode, String flightNumber, LocalDate serviceDate,
                             String departureAirport, String destinationAirport, String status, Instant checkInClosesAt) { }
}
