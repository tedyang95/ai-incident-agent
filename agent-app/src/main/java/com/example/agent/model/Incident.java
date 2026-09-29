package com.example.agent.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Incident 实体 - 存储每一条告警及其 AI 分析结果
 *
 * 对应面试中可以说的："I designed the incident data model with structured output
 * from LLM, including root cause hypothesis, confidence score, and suggested actions."
 */
@Entity
@Table(name = "incidents")
public class Incident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 告警基本信息
    @Column(nullable = false, columnDefinition = "TEXT")
    private String alertname;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String severity; // critical / warning / info

    @Column(nullable = false, columnDefinition = "TEXT")
    private String service; // 哪个服务触发的

    @Column(columnDefinition = "TEXT")
    private String category; // availability / error-rate / latency / resource

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String labelsJson; // 完整的告警标签（JSON）

    // 时间
    @Column(nullable = false)
    private LocalDateTime receivedAt;

    private LocalDateTime analyzedAt;

    private LocalDateTime resolvedAt;

    // AI 分析结果（结构化输出 structured output）
    @Column(columnDefinition = "TEXT")
    private String rootCauseHypothesis; // 根因假设

    @Column(columnDefinition = "TEXT")
    private String analysisDetail; // 详细分析

    @Column(columnDefinition = "TEXT")
    private String suggestedActions; // 建议的修复动作

    private Double confidence; // 置信度 0.0 - 1.0

    @Column(columnDefinition = "TEXT")
    private String relatedMetrics; // 相关指标摘要

    @Column(columnDefinition = "TEXT")
    private String relatedLogs; // 相关日志摘要

    @Column(columnDefinition = "TEXT")
    private String matchedRunbooks; // 匹配到的 runbook

    // 状态
    @Enumerated(EnumType.STRING)
    private AnalysisStatus status; // PENDING / ANALYZING / COMPLETED / FAILED

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    // LLM 调用追踪（tracing）
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
