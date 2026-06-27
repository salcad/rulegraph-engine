package com.interopera.rulegraph.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Lightweight liveness probe for orchestration and uptime checks. It does not touch downstream
 * dependencies, so a 200 response only indicates that the web process is up and accepting requests.
 */
@RestController
@RequestMapping("/rulegraph-api")
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
