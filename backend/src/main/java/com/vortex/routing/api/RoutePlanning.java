package com.vortex.routing.api;
import java.util.UUID;
public interface RoutePlanning { boolean plan(UUID bagId, UUID eventId, UUID correlationId); }
