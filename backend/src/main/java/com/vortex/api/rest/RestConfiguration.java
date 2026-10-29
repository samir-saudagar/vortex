package com.vortex.api.rest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class RestConfiguration implements WebMvcConfigurer {
    private final String origin;
    public RestConfiguration(@Value("${VORTEX_CORS_ORIGIN:http://localhost:5173}") String origin) { this.origin = origin; }
    @Override public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(origin).allowedMethods("GET", "POST")
                .allowedHeaders("Content-Type", "Idempotency-Key");
    }
}
