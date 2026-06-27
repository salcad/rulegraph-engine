package com.interopera.rulegraph.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cross-origin configuration for the API. The allowed origins are configurable so the front end can
 * call the backend directly when it is hosted on a different origin. The deployed stack serves the
 * front end through a same-origin nginx proxy, so CORS is not needed there at all; this simply makes
 * the direct-call option available (for example a local dev viewer).
 *
 * <p>Set {@code RULEGRAPH_CORS_ORIGINS} to a comma-separated list of origin patterns, for example
 * {@code https://app.example.com,https://*.example.com}.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String[] origins;

    public WebConfig(@Value("${rulegraph.cors-origins:http://localhost:5173,http://localhost:4173}")
                     String originList) {
        this.origins = originList.split("\\s*,\\s*");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/rulegraph-api/**")
                .allowedOriginPatterns(origins)
                // GET for reports/graph; POST for the firm-method preview and save endpoints.
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*");
    }
}
