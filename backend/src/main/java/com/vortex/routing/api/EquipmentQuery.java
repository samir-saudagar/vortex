package com.vortex.routing.api;
import java.time.Instant;
import java.util.*;
public interface EquipmentQuery {
    List<Item> equipment();
    Optional<Item> equipment(String id);
    record Item(String equipmentId, String type, String status, Instant lastStatusAt) { }
}
