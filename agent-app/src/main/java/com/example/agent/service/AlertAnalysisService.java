package com.example.agent.service;

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

import java.time.LocalDateTime;

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

        // 1b. Loki 错误日志
        String errors = lokiTool.getRecentErrors(incident.getService(), 15);
        String exceptions = lokiTool.getRecentExceptions(incident.getService(), 15);
        String logs = errors + "\n" + exceptions;
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

        // ============================================================
        // Step 2: 构建 Prompt（Prompt Engineering）
        // ============================================================
        log.info("Step 2: Building prompt for LLM analysis");

        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(incident, metrics, logs, runbooks);

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

        parseAndSaveAnalysis(incident, llmOutput);

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

                Output format (JSON only, no markdown):
                {
                  "root_cause_hypothesis": "One sentence summary of the most likely root cause",
                  "analysis_detail": "Detailed analysis referencing specific metrics and log evidence",
                  "suggested_actions": ["Action 1", "Action 2", "Action 3"],
                  "confidence": 0.0,
                  "evidence_strength": "strong|moderate|weak|insufficient"
                }
                """;
    }

    /**
     * 构建 User Prompt - 填入告警信息和收集到的上下文
     */
    private String buildUserPrompt(Incident incident, String metrics, String logs, String runbooks) {
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

                === LOGS (from Loki, last 15 minutes) ===
                %s

                === MATCHED RUNBOOKS (from knowledge base) ===
                %s

                === YOUR TASK ===
                Analyze this alert and provide a structured root cause analysis in JSON format.
                MANDATORY: quote at least one exact metric value (format: [metric: <name> = <value>])
                and at least one exact log line (format: [log: "<exact line>"]) inside
                root_cause_hypothesis or analysis_detail. Analysis without any quoted metric
                or log evidence will be treated as weak evidence (confidence <= 0.4).
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
                runbooks
        );
    }

    /**
     * 解析 LLM 的 JSON 输出并保存到 Incident
     */
    private void parseAndSaveAnalysis(Incident incident, String llmOutput) {
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

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "... [truncated]";
    }
}
