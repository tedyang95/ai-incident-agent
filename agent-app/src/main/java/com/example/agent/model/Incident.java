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

    @Column(columnDefinition = "TEXT")
    private String contextSnapshot; // 喂给 LLM 的完整上下文快照（JSON，审计/grounding 验证用）

    // 判别力字段（复合故障评估用）：
    // LLM 观察到的竞争信号（其他并发故障的迹象）与证据归属判断
    @Column(columnDefinition = "TEXT")
    private String competingSignals; // JSON 数组：competing_signals_observed

    @Column(columnDefinition = "TEXT")
    private String evidenceAlignment; // consistent / conflicting / insufficient

    // v9 证据链可执行化：LLM 引用编号 → 后端映射的真实查询 + 可点击深链 URL（JSON 数组）
    // 每条：{index, source, query, summary, window, url, resolved}
    // LLM 引用了不存在的编号 → resolved=false，不产生任何 query/url（无法伪造证据）
    @Column(columnDefinition = "TEXT")
    private String evidenceCitations;

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
