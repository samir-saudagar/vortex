package com.vortex.api.rest;

import com.vortex.api.LocalDevelopmentOrigins;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class RestConfiguration implements WebMvcConfigurer {
    private final String[] origins;
    public RestConfiguration(@Value("${VORTEX_CORS_ORIGIN:}") String additionalOrigins) {
        this.origins = LocalDevelopmentOrigins.including(additionalOrigins);
    }
    @Override public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(origins).allowedMethods("GET", "POST")
                .allowedHeaders("Content-Type", "Idempotency-Key");
    }
}
