package com.vortex.movement.api;
import java.util.*;
public interface MovementQuery {
    List<Map<String, Object>> commands(UUID bagId);
    List<Map<String, Object>> observations(UUID bagId);
    List<Map<String, Object>> progress(UUID bagId);
}
