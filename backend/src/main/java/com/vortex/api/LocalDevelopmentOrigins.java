package com.vortex.api;

import java.util.Arrays;
import java.util.stream.Stream;

/** Exact local UI origins shared by the REST and STOMP transports. */
public final class LocalDevelopmentOrigins {
    private LocalDevelopmentOrigins() { }

    public static String[] including(String additionalOrigins) {
        return Stream.concat(Stream.of("http://localhost:5173", "http://127.0.0.1:5173"),
                        Arrays.stream(additionalOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()))
                .peek(origin -> {
                    if (origin.contains("*")) throw new IllegalArgumentException("Development origins must be explicit; wildcards are not supported");
                })
                .distinct().toArray(String[]::new);
    }
}
