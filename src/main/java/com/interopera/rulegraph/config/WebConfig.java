package com.interopera.rulegraph.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cross-origin configuration for the API. The allowed origins are configurable so the front end can
 * call the backend directly when deployed (for example from a Vercel domain). When the front end is
 * served through a same-origin proxy (the nginx container, or a Vercel rewrite) CORS is not needed at
 * all; this simply makes the direct-call option available.
 *
 * <p>Set {@code RULEGRAPH_CORS_ORIGINS} to a comma-separated list of origin patterns, for example
 * {@code https://my-app.vercel.app,https://*.vercel.app}.
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
        registry.addMapping("/api/**")
                .allowedOriginPatterns(origins)
                // GET for reports/graph; POST for the firm-method preview and save endpoints.
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*");
    }
}
