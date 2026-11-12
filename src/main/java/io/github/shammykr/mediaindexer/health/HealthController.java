package io.github.shammykr.mediaindexer.health;

import java.time.Instant;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness endpoint. {@code /health} only says the JVM is up and serving requests;
 * dependency checks live in {@code /ready}.
 */
@RestController
public class HealthController {

    private final Instant startedAt = Instant.now();

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "startedAt", startedAt.toString());
    }
}
