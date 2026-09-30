package com.example.agent.controller;

import com.example.agent.model.Incident;
import com.example.agent.service.AlertAnalysisService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Alert webhook controller.
 * <p>
 * Receives alert notifications from Alertmanager and triggers the AI analysis
 * pipeline. Payload format reference:
 * https://prometheus.io/docs/alerting/latest/configuration/#webhook_config
 */
@RestController
@RequestMapping("/api/alert")
public class AlertWebhookController {

    private static final Logger log = LoggerFactory.getLogger(AlertWebhookController.class);
    private final AlertAnalysisService analysisService;

    public AlertWebhookController(AlertAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    /**
     * Receives an Alertmanager webhook.
     * POST /api/alert/webhook
     */
    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Object>> receiveAlert(@RequestBody JsonNode payload) {
        log.info("Received alert webhook: status={}, alertsCount={}",
                payload.path("status").asText(),
                payload.path("alerts").size());

        List<Map<String, Object>> results = new ArrayList<>();

        // Alertmanager may batch multiple alerts in a single webhook delivery.
        String requestedModel = payload.path("model").asText(null); // optional model alias
        for (JsonNode alert : payload.path("alerts")) {
            try {
                Incident incident = parseAlert(alert, requestedModel);
                Incident saved = analysisService.createAndAnalyze(incident);
                Map<String, Object> result = new HashMap<>();
                result.put("id", saved.getId());
                result.put("alertname", saved.getAlertname());
                result.put("status", saved.getStatus());
                result.put("confidence", saved.getConfidence());
                result.put("rootCause", saved.getRootCauseHypothesis());
                results.add(result);
            } catch (Exception e) {
                log.error("Failed to process alert: {}", e.getMessage(), e);
                Map<String, Object> error = new HashMap<>();
                error.put("error", e.getMessage());
                results.add(error);
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("processed", results.size());
        response.put("results", results);
        return ResponseEntity.ok(response);
    }

    /**
     * Parses a single Alertmanager alert into an Incident entity.
     *
     * @param alert    one entry of the webhook {@code alerts} array
     * @param model    the optional model alias requested by the caller (may be null)
     */
    private Incident parseAlert(JsonNode alert, String model) {
        Incident incident = new Incident();
        incident.setModel(model); // requested alias; resolved by the model registry at analysis time

        // Extract alert context from labels.
        JsonNode labels = alert.path("labels");
        incident.setAlertname(labels.path("alertname").asText("unknown"));
        incident.setSeverity(labels.path("severity").asText("warning"));
        incident.setService(labels.path("service").asText("unknown"));
        incident.setCategory(labels.path("category").asText("unknown"));

        // Extract the human-readable summary from annotations.
        JsonNode annotations = alert.path("annotations");
        incident.setDescription(
                annotations.path("summary").asText("") + " | " +
                annotations.path("description").asText("")
        );

        // Persist the full label set as JSON for auditability.
        incident.setLabelsJson(labels.toString());

        // Timing.
        String startsAt = alert.path("startsAt").asText(null);
        incident.setReceivedAt(LocalDateTime.now());

        incident.setStatus(Incident.AnalysisStatus.PENDING);

        log.info("Parsed alert: name={}, severity={}, service={}, category={}",
                incident.getAlertname(), incident.getSeverity(),
                incident.getService(), incident.getCategory());

        return incident;
    }

    /**
     * Manually triggers a test alert without Alertmanager.
     * POST /api/alert/test
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> triggerTestAlert(
            @RequestParam(defaultValue = "HighErrorRate") String alertname,
            @RequestParam(defaultValue = "critical") String severity,
            @RequestParam(defaultValue = "demo-app") String service) {

        Incident incident = new Incident();
        incident.setAlertname(alertname);
        incident.setSeverity(severity);
        incident.setService(service);
        incident.setCategory("test");
        incident.setDescription("Test alert triggered manually for " + service);
        incident.setReceivedAt(LocalDateTime.now());
        incident.setStatus(Incident.AnalysisStatus.PENDING);

        Incident saved = analysisService.createAndAnalyze(incident);

        Map<String, Object> response = new HashMap<>();
        response.put("id", saved.getId());
        response.put("status", saved.getStatus());
        response.put("rootCause", saved.getRootCauseHypothesis());
        response.put("confidence", saved.getConfidence());
        return ResponseEntity.ok(response);
    }
}
