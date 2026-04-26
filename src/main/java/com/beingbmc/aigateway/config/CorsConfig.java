package com.beingbmc.aigateway.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

@Configuration
public class CorsConfig implements WebFluxConfigurer {

    private static final String[] ALLOWED_ORIGINS = {
            "https://beingmartinbmc.github.io",
            "https://trisshasantos.github.io",
            "https://beingabu.github.io",
            "http://localhost:3000",
            "http://127.0.0.1:3000"
    };

    private static final String[] ALLOWED_METHODS = {
            "GET",
            "POST",
            "PUT",
            "DELETE",
            "PATCH",
            "OPTIONS"
    };

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(ALLOWED_ORIGINS)
                .allowedMethods(ALLOWED_METHODS)
                .allowedHeaders("*")
                .exposedHeaders("Content-Disposition", "X-RateLimit-Remaining", "Retry-After")
                .maxAge(3600);
    }
}
