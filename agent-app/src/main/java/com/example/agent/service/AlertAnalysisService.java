package com.example.agent.service;

import com.example.agent.config.AiModelRegistry;
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
 * Alert analysis service — the agent's core orchestration brain.
 * <p>
 * Analysis workflow:
 * 1. Persist the incoming alert
 * 2. Collect context:
 *    a. Prometheus metrics overview (metrics)
 *    b. Loki error logs and exceptions (logs)
 *    c. Runbook RAG retrieval (knowledge base)
 * 3. Build a structured prompt from all context
 * 4. LLM returns structured JSON: root cause, confidence, suggested actions
 * 5. Parse and persist the analysis result
 * 6. On LLM failure, degrade gracefully to a rule-based fallback
 * <p>
 * AI capability levels addressed:
 * - L2 Grounded AI (RAG) → runbook retrieval
 * - L3 Tool-using AI     → Prometheus + Loki tool calls
 * - L4 LLM Workflow      → fixed alert-analysis pipeline with structured output
 */
@Service
public class AlertAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AlertAnalysisService.class);

    private final IncidentRepository repository;
    private final PrometheusToolService prometheusTool;
    private final LokiToolService lokiTool;
    private final RunbookRetrievalService runbookRetrieval;
    private final AiModelRegistry modelRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AlertAnalysisService(
            IncidentRepository repository,
            PrometheusToolService prometheusTool,
            LokiToolService lokiTool,
            RunbookRetrievalService runbookRetrieval,
            AiModelRegistry modelRegistry) {
        this.repository = repository;
        this.prometheusTool = prometheusTool;
        this.lokiTool = lokiTool;
        this.runbookRetrieval = runbookRetrieval;
        this.modelRegistry = modelRegistry;
    }

    /**
     * Creates an Incident and triggers the AI analysis.
     * This is the main entry point called by the webhook controller.
     */
    public Incident createAndAnalyze(Incident incident) {
        // 1. Persist the initial record.
        incident.setStatus(Incident.AnalysisStatus.ANALYZING);
        incident = repository.save(incident);
        log.info("Incident #{} created: {} ({}/{})",
                incident.getId(), incident.getAlertname(),
                incident.getSeverity(), incident.getService());

        // 2. Run the analysis (synchronous in the MVP; can move to @Async later).
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
            // Fallback: at least keep the collected raw context for manual review.
            incident.setRootCauseHypothesis("Analysis failed - manual review required");
            incident.setConfidence(0.0);
        }

        return repository.save(incident);
    }

    /**
     * Core analysis flow.
     */
    private void analyze(Incident incident) {
        long startTime = System.currentTimeMillis();

        // ============================================================
        // Step 1: Context collection
        // ============================================================
        log.info("Step 1: Collecting context for incident #{}", incident.getId());

        // 1a. Prometheus metrics overview.
        String metrics = prometheusTool.getServiceOverview(incident.getService());
        incident.setRelatedMetrics(truncate(metrics, 2000));
        log.debug("Metrics collected: {} chars", metrics.length());

        // 1b. Loki log search driven by the alert category, within a correlation window.
        //     Retrieval discrimination:
        //     - latency keywords use only fault-injection markers (sleeping /
        //       "Latency fault"), dropping the generic "timeout" (a concurrent
        //       error's message "database connection timeout" would match it)
        //     - non-error-rate alerts exclude "RuntimeException" at the LogQL layer
        //       (concurrent error stack traces never enter this alert's context)
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

        // 1c. Runbook RAG retrieval.
        String runbooks = runbookRetrieval.retrieve(
                incident.getAlertname(),
                incident.getService(),
                incident.getCategory(),
                incident.getDescription()
        );
        incident.setMatchedRunbooks(truncate(runbooks, 900));
        log.debug("Runbooks retrieved: {} chars", runbooks.length());

        // 1d. Persist the full context snapshot (audit / reproducibility:
        //     exactly what the LLM saw, untruncated).
        try {
            Map<String, String> snapshot = new LinkedHashMap<>();
            snapshot.put("metrics", metrics);
            snapshot.put("logs", logs);
            snapshot.put("runbooks", runbooks);
            incident.setContextSnapshot(objectMapper.writeValueAsString(snapshot));
        } catch (Exception e) {
            log.warn("Failed to save context snapshot: {}", e.getMessage());
        }

        // 1e. Build the EVIDENCE INDEX (v9 executable citations).
        //     Real queries executed by the tools are recorded by the backend
        //     (single source of truth); the LLM may only cite index numbers and
        //     can never fabricate queries or URLs. Deep links (Grafana explore)
        //     are also generated by the backend.
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
        // Step 2: Build the prompt (prompt engineering)
        // ============================================================
        log.info("Step 2: Building prompt for LLM analysis");

        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(incident, metrics, logs, runbooks, evidenceIndexPrompt);

        // ============================================================
        // Step 3: Call the LLM
        // ============================================================
        log.info("Step 3: Calling LLM for root cause analysis");

        // Model selection: honor the caller's requested alias (webhook "model"
        // field); fall back to the default model for null/blank/unknown aliases.
        String modelName = modelRegistry.resolveName(incident.getModel());
        ChatClient client = modelRegistry.clientFor(modelName);
        log.info("Step 3: serving with model alias '{}'", modelName);

        ChatResponse response = client.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .chatResponse();

        String llmOutput = response.getResult().getOutput().getContent();
        log.debug("LLM output: {}", llmOutput);

        // Record token usage (tracing / cost tracking).
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            incident.setPromptTokens(response.getMetadata().getUsage().getPromptTokens().intValue());
            incident.setCompletionTokens(response.getMetadata().getUsage().getGenerationTokens().intValue());
        }
        incident.setModelUsed(modelName);

        // ============================================================
        // Step 4: Parse the structured output
        // ============================================================
        log.info("Step 4: Parsing structured LLM output");

        parseAndSaveAnalysis(incident, llmOutput, evidenceList);

        // Record analysis duration.
        incident.setAnalysisDurationMs(System.currentTimeMillis() - startTime);

        log.info("Analysis complete: rootCause='{}', confidence={}",
                incident.getRootCauseHypothesis(), incident.getConfidence());
    }

    /**
     * Builds the system prompt — defines the AI's role and output format.
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
     * Builds the user prompt — fills in the alert info and collected context.
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
     * Parses the LLM's JSON output and saves it to the Incident.
     */
    private void parseAndSaveAnalysis(Incident incident, String llmOutput, List<Evidence> evidenceList) {
        try {
            // Strip any markdown code-fence wrappers.
            String cleanJson = llmOutput.trim();
            if (cleanJson.startsWith("```")) {
                cleanJson = cleanJson.replaceAll("^```json\\s*", "").replaceAll("^```\\s*", "");
                cleanJson = cleanJson.replaceAll("\\s*```$", "");
            }

            JsonNode json = objectMapper.readTree(cleanJson);

            incident.setRootCauseHypothesis(json.path("root_cause_hypothesis").asText("Unknown"));
            incident.setAnalysisDetail(json.path("analysis_detail").asText(""));

            // Convert the suggested_actions array to a string.
            if (json.has("suggested_actions") && json.get("suggested_actions").isArray()) {
                StringBuilder actions = new StringBuilder();
                for (JsonNode action : json.get("suggested_actions")) {
                    actions.append("- ").append(action.asText()).append("\n");
                }
                incident.setSuggestedActions(actions.toString());
            }

            incident.setConfidence(json.path("confidence").asDouble(0.0));

            // Discrimination fields (competing signals / evidence alignment):
            // used by composite-fault evaluation to quantify whether the system
            // recognized concurrent signals while keeping its discrimination.
            incident.setCompetingSignals(json.path("competing_signals_observed").toString());
            incident.setEvidenceAlignment(json.path("evidence_alignment").asText(""));

            // v9 executable citations: resolve the [E{n}] indexes cited by the LLM
            // to the real queries and deep links recorded by the backend.
            incident.setEvidenceCitations(buildEvidenceCitationsJson(llmOutput, evidenceList));

            log.info("Parsed analysis: confidence={}, rootCause={}",
                    incident.getConfidence(), incident.getRootCauseHypothesis());

        } catch (Exception e) {
            log.error("Failed to parse LLM output as JSON: {}. Raw output: {}", e.getMessage(), llmOutput);
            // Fallback: keep the raw output for human review.
            incident.setRootCauseHypothesis("Failed to parse structured output - see analysis_detail");
            incident.setAnalysisDetail(llmOutput);
            incident.setConfidence(0.3);
        }
    }

    /**
     * Renders the EVIDENCE INDEX into the prompt (v9).
     * One line per entry: index + source + real query + summary + window.
     * The LLM may only cite these indexes.
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
     * Resolves every {@code [E{n}]} index cited by the LLM into an executable
     * citation (v9 core).
     * <p>
     * Key security design: query and url come only from the backend
     * evidenceList (queries the tools really executed). If the LLM cites an
     * index that does not exist, the citation is resolved=false with no
     * query/url — the model cannot fabricate evidence.
     */
    private String buildEvidenceCitationsJson(String llmOutput, List<Evidence> evidenceList) {
        try {
            Set<Integer> cited = new LinkedHashSet<>();
            Matcher m = Pattern.compile("\\[E(\\d+)]").matcher(llmOutput == null ? "" : llmOutput);
            while (m.find()) {
                try {
                    cited.add(Integer.parseInt(m.group(1)));
                } catch (NumberFormatException ignored) {
                    // Non-numeric bracket text (e.g. [Error]) — ignore.
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
                    c.put("resolved", false); // fabricated/unknown index: not resolvable, no query/url
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
     * Builds a Grafana Explore deep-link URL (assembled backend-side; the LLM
     * never participates). One click reproduces the evidence query in Grafana —
     * the visual landing point of the "executable evidence chain".
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
