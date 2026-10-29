package com.vortex.movement.api;
import java.util.UUID;
public interface MovementStarter { boolean start(UUID bagId, UUID routeId, UUID eventId, UUID correlationId); }
