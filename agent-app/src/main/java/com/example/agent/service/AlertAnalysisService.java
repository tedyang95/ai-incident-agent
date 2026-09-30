package com.example.agent.service;

import com.example.agent.evidence.Evidence;
import com.example.agent.evidence.QueryRecord;
import com.example.agent.model.Incident;
import com.example.agent.rag.RunbookRetrievalService;
import com.example.agent.repository.IncidentRepository;
import com.example.agent.tool.LokiToolService;
import com.example.agent.tool.PrometheusToolService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 告警分析服务 - AI Agent 的核心编排（orchestration）
 * <p>
 * 分析流程（LLM Workflow）：
 * 1. 保存告警到数据库
 * 2. 收集上下文（context collection）：
 * a. Prometheus 指标概览（metrics）
 * b. Loki 错误日志和异常（logs）
 * c. Runbook RAG 检索（knowledge base）
 * 3. 构建结构化 prompt，把所有上下文喂给 LLM
 * 4. LLM 输出结构化 JSON（structured output）：根因、置信度、建议动作
 * 5. 解析 JSON，保存分析结果
 * 6. 异常处理：LLM 失败时降级为规则分析（fallback）
 * <p>
 * 对应 AI 产品六层框架：
 * - L2 Grounded AI (RAG) → runbook 检索
 * - L3 Tool-using AI → Prometheus + Loki 工具调用
 * - L4 LLM Workflow → 固定的告警分析流程
 * <p>
 * 面试中可以说："I designed and implemented the LLM workflow that orchestrates
 * metric retrieval, log search, RAG, and structured output generation, with
 * fallback mechanisms and full tracing for every LLM call."
 */
@Service
public class AlertAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AlertAnalysisService.class);

    private final IncidentRepository repository;
    private final PrometheusToolService prometheusTool;
    private final LokiToolService lokiTool;
    private final RunbookRetrievalService runbookRetrieval;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AlertAnalysisService(
            IncidentRepository repository,
            PrometheusToolService prometheusTool,
            LokiToolService lokiTool,
            RunbookRetrievalService runbookRetrieval,
            ChatClient.Builder chatClientBuilder) {
        this.repository = repository;
        this.prometheusTool = prometheusTool;
        this.lokiTool = lokiTool;
        this.runbookRetrieval = runbookRetrieval;
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * 创建 Incident 并触发 AI 分析
     * 这是 webhook controller 调用的主入口
     */
    public Incident createAndAnalyze(Incident incident) {
        // 1. 保存初始记录
        incident.setStatus(Incident.AnalysisStatus.ANALYZING);
        incident = repository.save(incident);
        log.info("Incident #{} created: {} ({}/{})",
                incident.getId(), incident.getAlertname(),
                incident.getSeverity(), incident.getService());

        // 2. 异步或同步分析（MVP 用同步，后续可改 @Async）
        try {
            analyze(incident);
            incident.setStatus(Incident.AnalysisStatus.COMPLETED);
            incident.setAnalyzedAt(LocalDateTime.now());
            log.info("Incident #{} analyzed successfully, confidence={}",
                    incident.getId(), incident.getConfidence());
        } catch (Exception e) {
            log.error("Incident #{} analysis failed: {}", incident.getId(), e.getMessage(), e);
            incident.setStatus(Incident.AnalysisStatus.FAILED);
            incident.setErrorMessage(truncate(e.getMessage(), 900));
            // Fallback: 至少保存收集到的原始数据
            incident.setRootCauseHypothesis("Analysis failed - manual review required");
            incident.setConfidence(0.0);
        }

        return repository.save(incident);
    }

    /**
     * 核心分析流程
     */
    private void analyze(Incident incident) {
        long startTime = System.currentTimeMillis();

        // ============================================================
        // Step 1: 收集上下文（Context Collection）
        // ============================================================
        log.info("Step 1: Collecting context for incident #{}", incident.getId());

        // 1a. Prometheus 指标概览
        String metrics = prometheusTool.getServiceOverview(incident.getService());
        incident.setRelatedMetrics(truncate(metrics, 2000));
        log.debug("Metrics collected: {} chars", metrics.length());

        // 1b. Loki 日志检索（告警语义关键词 + correlation window）
        //     关键词由告警类别驱动：latency 告警也搜 slow/sleep/timeout 信号，
        //     而不只查 ERROR/Exception（latency 故障通常是 WARN/DEBUG 日志，无异常堆栈）
        //
        //     检索层判别（retrieval discrimination）：
        //     - latency 关键词只用故障注入独有的标记（sleeping / Latency fault），
        //       去掉通用 "timeout"（并发 error 故障的异常信息 "database connection
        //       timeout" 恰好含该词，会造成证据污染）
        //     - 非 error-rate 告警在 LogQL 层排除 "RuntimeException"：并发 error 故障
        //       的异常堆栈不会进入本告警上下文（!= 运算符）
        Instant alertInstant = incident.getReceivedAt().toInstant(ZoneOffset.UTC);
        Instant logFrom = alertInstant.minus(2, ChronoUnit.MINUTES);
        Set<String> keywords = new LinkedHashSet<>();
        keywords.add("ERROR");
        keywords.add("Exception");
        String cat = incident.getCategory() == null ? "" : incident.getCategory();
        String excludeKeyword = "error-rate".equals(cat) ? null : "RuntimeException";
        switch (cat) {
            case "latency" -> keywords.addAll(List.of("sleeping", "Latency fault"));
            case "resource" -> keywords.addAll(List.of("OutOfMemory", "memory"));
            case "error-rate" -> keywords.addAll(List.of("RuntimeException"));
            default -> { }
        }
        StringBuilder logsBuilder = new StringBuilder();
        for (String kw : keywords) {
            logsBuilder.append(lokiTool.searchLogsBetween(
                    incident.getService(), kw, logFrom, alertInstant, 10, excludeKeyword)).append("\n");
        }
        String logs = logsBuilder.toString();
        incident.setRelatedLogs(truncate(logs, 2000));
        log.debug("Logs collected: {} chars", logs.length());

        // 1c. Runbook RAG 检索
        String runbooks = runbookRetrieval.retrieve(
                incident.getAlertname(),
                incident.getService(),
                incident.getCategory(),
                incident.getDescription()
        );
        incident.setMatchedRunbooks(truncate(runbooks, 900));
        log.debug("Runbooks retrieved: {} chars", runbooks.length());

        // 1d. 存完整上下文快照（审计/可复现性：LLM 实际看到的原文，未截断）
        try {
            Map<String, String> snapshot = new LinkedHashMap<>();
            snapshot.put("metrics", metrics);
            snapshot.put("logs", logs);
            snapshot.put("runbooks", runbooks);
            incident.setContextSnapshot(objectMapper.writeValueAsString(snapshot));
        } catch (Exception e) {
            log.warn("Failed to save context snapshot: {}", e.getMessage());
        }

        // 1e. 构建 EVIDENCE INDEX（v9 证据链可执行化）
        //     真实执行的查询由后端工具记录（single source of truth），LLM 只能引用编号，
        //     无法编造 query/URL。深链 URL（Grafana explore）也由后端生成。
        List<Evidence> evidenceList = new ArrayList<>();
        int idx = 1;
        for (QueryRecord qr : prometheusTool.getLastQueryRecords()) {
            evidenceList.add(Evidence.of(idx++, "PROMETHEUS", qr.query(), qr.summary(), qr.window(),
                    buildGrafanaExploreUrl("prometheus", qr.query())));
        }
        QueryRecord lokiRecord = lokiTool.getLastQueryRecord();
        if (lokiRecord != null) {
            evidenceList.add(Evidence.of(idx++, "LOKI", lokiRecord.query(), lokiRecord.summary(),
                    lokiRecord.window(), buildGrafanaExploreUrl("loki", lokiRecord.query())));
        }
        evidenceList.add(Evidence.of(idx, "RUNBOOK",
                "RAG: alertname=" + incident.getAlertname() + ", category=" + incident.getCategory(),
                "Retrieved from runbook knowledge base", "alert-time", null));
        String evidenceIndexPrompt = buildEvidenceIndexPrompt(evidenceList);
        log.debug("Evidence index built: {} entries", evidenceList.size());

        // ============================================================
        // Step 2: 构建 Prompt（Prompt Engineering）
        // ============================================================
        log.info("Step 2: Building prompt for LLM analysis");

        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(incident, metrics, logs, runbooks, evidenceIndexPrompt);

        // ============================================================
        // Step 3: 调用 LLM（LLM Call）
        // ============================================================
        log.info("Step 3: Calling LLM for root cause analysis");

        ChatResponse response = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .chatResponse();

        String llmOutput = response.getResult().getOutput().getContent();
        log.debug("LLM output: {}", llmOutput);

        // 记录 token 使用（tracing / cost tracking）
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            incident.setPromptTokens(response.getMetadata().getUsage().getPromptTokens().intValue());
            incident.setCompletionTokens(response.getMetadata().getUsage().getGenerationTokens().intValue());
        }
        incident.setModelUsed("gpt-4o-mini");

        // ============================================================
        // Step 4: 解析结构化输出（Structured Output Parsing）
        // ============================================================
        log.info("Step 4: Parsing structured LLM output");

        parseAndSaveAnalysis(incident, llmOutput, evidenceList);

        // 记录分析耗时
        incident.setAnalysisDurationMs(System.currentTimeMillis() - startTime);

        log.info("Analysis complete: rootCause='{}', confidence={}",
                incident.getRootCauseHypothesis(), incident.getConfidence());
    }

    /**
     * 构建 System Prompt - 定义 AI 角色和输出格式
     */
    private String buildSystemPrompt() {
        return """
                You are an expert SRE (Site Reliability Engineer) and DevOps engineer
                specializing in root cause analysis (RCA) for microservices architectures.

                Your job is to analyze production alerts by examining metrics, logs,
                and runbooks, then provide a structured root cause analysis.

                Rules:
                1. Base your analysis ONLY on the provided context (metrics, logs, runbooks).
                2. If the evidence is insufficient, say so explicitly and give a confidence score below 0.5.
                3. Distinguish between "what the data shows" and "your hypothesis".
                4. Provide concrete, actionable remediation steps, not generic advice.
                5. Output MUST be valid JSON with the exact fields specified.
                6. EVIDENCE CITATION (MANDATORY): In root_cause_hypothesis, you MUST quote
                   at least one exact metric value from the METRICS section (format:
                   [metric: <name> = <value>]) AND at least one exact log line from the
                   LOGS section (format: [log: "<exact line>"]). NEVER write a root cause
                   with no concrete numbers or log quotes. Generic answers like "memory leak
                   or excessive memory consumption by the application" are UNACCEPTABLE.
                7. CONFIDENCE CALIBRATION: confidence must match how much quoted evidence
                   you cite: 0.7-0.9 = confirmed by metric AND log evidence; 0.5-0.6 = partial
                   evidence; 0.2-0.4 = weak/indirect evidence; below 0.2 = no evidence.
                   evidence_strength must be consistent: strong = quoted metric + log,
                   moderate = one quoted piece, weak/insufficient = none.
                8. DISCRIMINATION AGAINST CONCURRENT SIGNALS (CRITICAL): The retrieved
                   context MAY contain evidence from OTHER, concurrent incidents — this is
                   expected in production. Before concluding:
                   a. Identify signals that do NOT match THIS alert's metric signature.
                      Metric signature means: error-rate alerts are driven by error rate /
                      5xx counts; latency alerts by p99/avg latency; memory alerts by JVM
                      heap usage. Evidence that does NOT move THIS alert's metric belongs
                      to a different fault mode.
                   b. Report such signals in competing_signals_observed. Do NOT let them
                      become your primary root cause.
                   c. If competing signals cannot be fully excluded, cap confidence at 0.5
                      and set evidence_alignment to "conflicting".
                9. REASONING ORDER: work through 3 steps explicitly in analysis_detail —
                   (1) evidence most relevant to THIS alert's metric signature;
                   (2) competing signals observed and why they belong to a different
                   fault mode; (3) final conclusion.
                10. EVIDENCE CITATION BY INDEX (MANDATORY): The EVIDENCE INDEX section
                   lists the REAL queries that were actually executed, each numbered
                   [E1], [E2], ... . Whenever you reference evidence in root_cause_hypothesis
                   or analysis_detail, cite its number as [E{n}] next to the quoted
                   metric value or log line. NEVER invent index numbers, queries, or
                   URLs — only the [E{n}] entries from the EVIDENCE INDEX can be resolved.
                   Invented citations make your analysis untrustworthy.

                Output format (JSON only, no markdown):
                {
                  "root_cause_hypothesis": "One sentence summary of the most likely root cause",
                  "analysis_detail": "Detailed analysis referencing specific metrics and log evidence",
                  "suggested_actions": ["Action 1", "Action 2", "Action 3"],
                  "competing_signals_observed": ["signal 1", "signal 2"],
                  "evidence_alignment": "consistent|conflicting|insufficient",
                  "confidence": 0.0,
                  "evidence_strength": "strong|moderate|weak|insufficient"
                }
                """;
    }

    /**
     * 构建 User Prompt - 填入告警信息和收集到的上下文
     */
    private String buildUserPrompt(Incident incident, String metrics, String logs, String runbooks,
                                   String evidenceIndexPrompt) {
        return """
                === ALERT INFORMATION ===
                Alert Name: %s
                Severity: %s
                Service: %s
                Category: %s
                Description: %s
                Received At: %s

                === METRICS (from Prometheus) ===
                %s

                === LOGS (from Loki, correlation window around alert time) ===
                %s

                === MATCHED RUNBOOKS (from knowledge base) ===
                %s

                === EVIDENCE INDEX (real queries executed by the tools; cite ONLY by number) ===
                %s

                === YOUR TASK ===
                Analyze this alert and provide a structured root cause analysis in JSON format.
                MANDATORY: quote at least one exact metric value (format: [metric: <name> = <value>])
                and at least one exact log line (format: [log: "<exact line>"]) inside
                root_cause_hypothesis or analysis_detail. Analysis without any quoted metric
                or log evidence will be treated as weak evidence (confidence <= 0.4).
                MANDATORY: every evidence reference must cite its EVIDENCE INDEX number as [E{n}]
                next to the quoted value/log line. NEVER invent index numbers — only [E{n}]
                from the EVIDENCE INDEX exist, and invented ones cannot be resolved.
                If the logs show errors or exceptions, quote them and explain their significance.
                """.formatted(
                incident.getAlertname(),
                incident.getSeverity(),
                incident.getService(),
                incident.getCategory(),
                incident.getDescription(),
                incident.getReceivedAt().toString(),
                metrics,
                logs,
                runbooks,
                evidenceIndexPrompt
        );
    }

    /**
     * 解析 LLM 的 JSON 输出并保存到 Incident
     */
    private void parseAndSaveAnalysis(Incident incident, String llmOutput, List<Evidence> evidenceList) {
        try {
            // 清理可能的 markdown 代码块标记
            String cleanJson = llmOutput.trim();
            if (cleanJson.startsWith("```")) {
                cleanJson = cleanJson.replaceAll("^```json\\s*", "").replaceAll("^```\\s*", "");
                cleanJson = cleanJson.replaceAll("\\s*```$", "");
            }

            JsonNode json = objectMapper.readTree(cleanJson);

            incident.setRootCauseHypothesis(json.path("root_cause_hypothesis").asText("Unknown"));
            incident.setAnalysisDetail(json.path("analysis_detail").asText(""));

            // 把 suggested_actions 数组转成字符串
            if (json.has("suggested_actions") && json.get("suggested_actions").isArray()) {
                StringBuilder actions = new StringBuilder();
                for (JsonNode action : json.get("suggested_actions")) {
                    actions.append("- ").append(action.asText()).append("\n");
                }
                incident.setSuggestedActions(actions.toString());
            }

            incident.setConfidence(json.path("confidence").asDouble(0.0));

            // 判别力字段（competing signals / evidence alignment）：
            // 复合故障评估用，可量化"系统是否识别出并发信号并保持判别力"
            incident.setCompetingSignals(json.path("competing_signals_observed").toString());
            incident.setEvidenceAlignment(json.path("evidence_alignment").asText(""));

            // v9 证据链可执行化：LLM 引用的 [E{n}] → 后端映射为真实查询 + 深链 URL
            incident.setEvidenceCitations(buildEvidenceCitationsJson(llmOutput, evidenceList));

            log.info("Parsed analysis: confidence={}, rootCause={}",
                    incident.getConfidence(), incident.getRootCauseHypothesis());

        } catch (Exception e) {
            log.error("Failed to parse LLM output as JSON: {}. Raw output: {}", e.getMessage(), llmOutput);
            // Fallback: 把原始输出存到 analysis_detail
            incident.setRootCauseHypothesis("Failed to parse structured output - see analysis_detail");
            incident.setAnalysisDetail(llmOutput);
            incident.setConfidence(0.3);
        }
    }

    /**
     * 把 EVIDENCE INDEX 渲染进 prompt（v9）。
     * 每一行：编号 + 来源 + 真实查询 + 摘要 + 时间窗。LLM 只能引用这些编号。
     */
    private String buildEvidenceIndexPrompt(List<Evidence> evidenceList) {
        if (evidenceList.isEmpty()) {
            return "(no evidence registered)";
        }
        StringBuilder sb = new StringBuilder();
        for (Evidence e : evidenceList) {
            sb.append("[E").append(e.index()).append("] ")
                    .append(e.source())
                    .append(" | query: ").append(e.query())
                    .append(" | summary: ").append(e.summary())
                    .append(" | window: ").append(e.window())
                    .append("\n");
        }
        return sb.toString();
    }

    /**
     * 把 LLM 输出中引用的 [E{n}] 编号映射为可执行 citation（v9 核心）。
     * <p>
     * 关键安全设计：query 和 url 只来自后端的 evidenceList（工具真实执行的查询），
     * LLM 引用不存在的编号 → resolved=false 且不含任何 query/url——LLM 无法伪造证据。
     */
    private String buildEvidenceCitationsJson(String llmOutput, List<Evidence> evidenceList) {
        try {
            Set<Integer> cited = new LinkedHashSet<>();
            Matcher m = Pattern.compile("\\[E(\\d+)]").matcher(llmOutput == null ? "" : llmOutput);
            while (m.find()) {
                try {
                    cited.add(Integer.parseInt(m.group(1)));
                } catch (NumberFormatException ignored) {
                    // 非数字编号（如 [Error]），忽略
                }
            }

            Map<Integer, Evidence> byIndex = new LinkedHashMap<>();
            for (Evidence e : evidenceList) {
                byIndex.put(e.index(), e);
            }

            List<Map<String, Object>> citations = new ArrayList<>();
            for (Integer i : cited) {
                Evidence e = byIndex.get(i);
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("index", i);
                if (e != null) {
                    c.put("source", e.source());
                    c.put("query", e.query());
                    c.put("summary", e.summary());
                    c.put("window", e.window());
                    if (e.url() != null) {
                        c.put("url", e.url());
                    }
                    c.put("resolved", true);
                } else {
                    c.put("resolved", false); // 伪造/不存在的编号：不可解析，无 query/url
                }
                citations.add(c);
            }
            return objectMapper.writeValueAsString(citations);
        } catch (Exception e) {
            log.warn("Failed to build evidence citations: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 生成 Grafana Explore 深链 URL（后端拼装，LLM 不参与）。
     * 点击即可在 Grafana 中复现该证据查询——"证据链可执行化"的可视化落点。
     */
    private String buildGrafanaExploreUrl(String datasource, String expr) {
        try {
            String pane = "{\"pane1\":{\"datasource\":\"" + datasource
                    + "\",\"queries\":[{\"refId\":\"A\",\"expr\":"
                    + objectMapper.writeValueAsString(expr)
                    + "}],\"range\":{\"from\":\"now-30m\",\"to\":\"now\"}}}";
            return "http://localhost:3000/explore?schemaVersion=1&panes="
                    + URLEncoder.encode(pane, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Failed to build Grafana explore URL: {}", e.getMessage());
            return null;
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "... [truncated]";
    }
}
