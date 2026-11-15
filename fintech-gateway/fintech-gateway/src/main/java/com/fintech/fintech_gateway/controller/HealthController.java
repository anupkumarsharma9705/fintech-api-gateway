package com.fintech.fintech_gateway.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Lightweight liveness probe. It intentionally does not call MySQL, Redis or
 * the backend: a liveness check that depends on every downstream service
 * turns a single dependency outage into a rolling restart of healthy gateway
 * instances. Dependency readiness is exposed by Actuator at
 * /actuator/health/readiness.
 */
@RestController
@RequestMapping("/health")
public class HealthController {

    @GetMapping
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "fintech-gateway"
        ));
    }
}
