package com.example.agent.controller;

import com.example.agent.model.Incident;
import com.example.agent.repository.IncidentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Incident query API — consumed by dashboards and external tooling.
 * GET /api/incidents      → paginated list
 * GET /api/incidents/{id} → single incident detail
 */
@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentRepository repository;

    public IncidentController(IncidentRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ResponseEntity<Page<Incident>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String service) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "receivedAt"));

        Page<Incident> results;
        if (severity != null && service != null) {
            results = repository.findBySeverityAndService(severity, service, pageable);
        } else if (severity != null) {
            results = repository.findBySeverity(severity, pageable);
        } else if (service != null) {
            results = repository.findByService(service, pageable);
        } else {
            results = repository.findAll(pageable);
        }

        return ResponseEntity.ok(results);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Incident> getById(@PathVariable Long id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        long total = repository.count();
        long critical = repository.countBySeverity("critical");
        long warning = repository.countBySeverity("warning");
        long completed = repository.countByStatus(Incident.AnalysisStatus.COMPLETED);
        long failed = repository.countByStatus(Incident.AnalysisStatus.FAILED);

        return ResponseEntity.ok(Map.of(
                "total", total,
                "critical", critical,
                "warning", warning,
                "completed", completed,
                "failed", failed
        ));
    }
}
