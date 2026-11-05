package com.vortex.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalDevelopmentOriginsTest {
    @Test void bothRequiredOriginsRemainWhenExistingSingleOriginSettingsArePresent() {
        assertArrayEquals(new String[]{"http://localhost:5173", "http://127.0.0.1:5173"},
                LocalDevelopmentOrigins.including("http://127.0.0.1:5173"));
        assertArrayEquals(new String[]{"http://localhost:5173", "http://127.0.0.1:5173", "http://localhost:5174"},
                LocalDevelopmentOrigins.including(" http://localhost:5174, ,http://localhost:5174 "));
    }
    @Test void unrestrictedOrPatternOriginsAreNotAccepted() {
        for (String origin : new String[]{"*", "http://*.localhost:5173"})
            assertThrows(IllegalArgumentException.class, () -> LocalDevelopmentOrigins.including(origin));
    }
}
