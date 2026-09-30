package com.example.agent.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Incident entity — persists each alert together with its AI analysis result.
 * <p>
 * The data model captures both the raw alert context (labels, timing) and the
 * structured LLM output (root cause, confidence, suggested actions), plus
 * evaluation artifacts (competing signals, evidence alignment, citations).
 */
@Entity
@Table(name = "incidents")
public class Incident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Alert metadata
    @Column(nullable = false, columnDefinition = "TEXT")
    private String alertname;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String severity; // critical / warning / info

    @Column(nullable = false, columnDefinition = "TEXT")
    private String service; // the service that fired the alert

    @Column(columnDefinition = "TEXT")
    private String category; // availability / error-rate / latency / resource

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String labelsJson; // full alert labels (JSON)

    // Timing
    @Column(nullable = false)
    private LocalDateTime receivedAt;

    private LocalDateTime analyzedAt;

    private LocalDateTime resolvedAt;

    // AI analysis result (structured LLM output)
    @Column(columnDefinition = "TEXT")
    private String rootCauseHypothesis; // root-cause hypothesis

    @Column(columnDefinition = "TEXT")
    private String analysisDetail; // detailed analysis

    @Column(columnDefinition = "TEXT")
    private String suggestedActions; // suggested remediation actions

    private Double confidence; // 0.0 - 1.0

    @Column(columnDefinition = "TEXT")
    private String relatedMetrics; // collected metrics summary

    @Column(columnDefinition = "TEXT")
    private String relatedLogs; // collected logs summary

    @Column(columnDefinition = "TEXT")
    private String matchedRunbooks; // matched runbooks

    @Column(columnDefinition = "TEXT")
    private String contextSnapshot; // full context fed to the LLM (JSON; audit / grounding checks)

    // Discrimination fields (composite-fault evaluation):
    // concurrent signals the LLM observed, and how the evidence aligns with
    // this alert's metric signature.
    @Column(columnDefinition = "TEXT")
    private String competingSignals; // JSON array: competing_signals_observed

    @Column(columnDefinition = "TEXT")
    private String evidenceAlignment; // consistent / conflicting / insufficient

    // v9 executable evidence citations: the LLM cites index numbers [E{n}], and
    // the backend resolves them to the real executed queries + clickable deep links
    // (JSON array). Invented indexes resolve to resolved=false with no query/url —
    // the model cannot fabricate evidence.
    @Column(columnDefinition = "TEXT")
    private String evidenceCitations;

    // State
    @Enumerated(EnumType.STRING)
    private AnalysisStatus status; // PENDING / ANALYZING / COMPLETED / FAILED

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    // LLM call tracing
    private String modelUsed;
    private Integer promptTokens;
    private Integer completionTokens;
    private Long analysisDurationMs;

    public enum AnalysisStatus {
        PENDING, ANALYZING, COMPLETED, FAILED
    }

    // ============================================================
    // Getters and Setters
    // ============================================================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getAlertname() { return alertname; }
    public void setAlertname(String alertname) { this.alertname = alertname; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getService() { return service; }
    public void setService(String service) { this.service = service; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getLabelsJson() { return labelsJson; }
    public void setLabelsJson(String labelsJson) { this.labelsJson = labelsJson; }

    public LocalDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(LocalDateTime receivedAt) { this.receivedAt = receivedAt; }

    public LocalDateTime getAnalyzedAt() { return analyzedAt; }
    public void setAnalyzedAt(LocalDateTime analyzedAt) { this.analyzedAt = analyzedAt; }

    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }

    public String getRootCauseHypothesis() { return rootCauseHypothesis; }
    public void setRootCauseHypothesis(String rootCauseHypothesis) { this.rootCauseHypothesis = rootCauseHypothesis; }

    public String getAnalysisDetail() { return analysisDetail; }
    public void setAnalysisDetail(String analysisDetail) { this.analysisDetail = analysisDetail; }

    public String getSuggestedActions() { return suggestedActions; }
    public void setSuggestedActions(String suggestedActions) { this.suggestedActions = suggestedActions; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public String getRelatedMetrics() { return relatedMetrics; }
    public void setRelatedMetrics(String relatedMetrics) { this.relatedMetrics = relatedMetrics; }

    public String getRelatedLogs() { return relatedLogs; }
    public void setRelatedLogs(String relatedLogs) { this.relatedLogs = relatedLogs; }

    public String getMatchedRunbooks() { return matchedRunbooks; }
    public void setMatchedRunbooks(String matchedRunbooks) { this.matchedRunbooks = matchedRunbooks; }

    public String getContextSnapshot() { return contextSnapshot; }
    public void setContextSnapshot(String contextSnapshot) { this.contextSnapshot = contextSnapshot; }

    public String getCompetingSignals() { return competingSignals; }
    public void setCompetingSignals(String competingSignals) { this.competingSignals = competingSignals; }

    public String getEvidenceAlignment() { return evidenceAlignment; }
    public void setEvidenceAlignment(String evidenceAlignment) { this.evidenceAlignment = evidenceAlignment; }

    public String getEvidenceCitations() { return evidenceCitations; }
    public void setEvidenceCitations(String evidenceCitations) { this.evidenceCitations = evidenceCitations; }

    public AnalysisStatus getStatus() { return status; }
    public void setStatus(AnalysisStatus status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getModelUsed() { return modelUsed; }
    public void setModelUsed(String modelUsed) { this.modelUsed = modelUsed; }

    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }

    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }

    public Long getAnalysisDurationMs() { return analysisDurationMs; }
    public void setAnalysisDurationMs(Long analysisDurationMs) { this.analysisDurationMs = analysisDurationMs; }
}
