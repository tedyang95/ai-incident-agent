package com.example.agent.controller;

import com.example.agent.config.AiModelRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Model discovery endpoint.
 * <p>
 * Exposes which model aliases the agent can serve, so callers (and operators)
 * can discover valid values for the webhook's optional {@code model} field —
 * the same discovery pattern as a capability listing on a platform API.
 */
@RestController
@RequestMapping("/api/models")
public class ModelInfoController {

    private final AiModelRegistry modelRegistry;

    public ModelInfoController(AiModelRegistry modelRegistry) {
        this.modelRegistry = modelRegistry;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listModels() {
        return ResponseEntity.ok(Map.of(
                "default", modelRegistry.defaultName(),
                "models", modelRegistry.list()
        ));
    }
}
