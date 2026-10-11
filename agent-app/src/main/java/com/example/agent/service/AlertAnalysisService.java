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

    // Deterministic confidence guardrail for heap/memory alerts. Must match the
    // HighMemoryUsage alert expression (G1 Old Gen heap used/max > 0.85).
    private static final double HEAP_ALERT_THRESHOLD = 0.85;
    private static final double HEAP_CONFIDENCE_FLOOR = 0.7;
    private static final Pattern HEAP_CITATION = Pattern.compile(
            "\\[metric:[^]]*heap[^]]*=\\s*([0-9]*\\.?[0-9]+)\\s*]", Pattern.CASE_INSENSITIVE);

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
        //     CATEGORY-SCOPED RETRIEVAL (v12): each alert class only queries ITS OWN
        //     fault markers as log-entry keywords. Latency/resource alerts no longer
        //     query generic "ERROR"/"Exception" — concurrent faults' stack traces
        //     (e.g. SQLTransientConnectionException, downstream timeouts) never enter
        //     this alert's context, so the LLM cannot be pulled toward them.
        //     error-rate keeps the generic entries because its root cause IS an
        //     exception type.
        Instant alertInstant = incident.getReceivedAt().toInstant(ZoneOffset.UTC);
        Instant logFrom = alertInstant.minus(2, ChronoUnit.MINUTES);
        Set<String> keywords = new LinkedHashSet<>();
        String cat = incident.getCategory() == null ? "" : incident.getCategory();
        switch (cat) {
            case "latency" -> keywords.addAll(List.of("sleeping", "Latency fault"));
            case "resource" -> keywords.addAll(List.of("OutOfMemory", "memory"));
            case "error-rate" -> keywords.addAll(List.of("ERROR", "Exception", "RuntimeException"));
            default -> keywords.addAll(List.of("ERROR", "Exception"));
        }
        StringBuilder logsBuilder = new StringBuilder();
        for (String kw : keywords) {
            logsBuilder.append(lokiTool.searchLogsBetween(
                    incident.getService(), kw, logFrom, alertInstant, 10, null)).append("\n");
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
                   A HEALTHY conclusion is an insufficient-evidence outcome, not a confirmed
                   diagnosis: if this alert's metric signature shows no anomaly and no
                   matching logs exist, state "no anomaly detected" and set confidence <= 0.5
                   with evidence_strength "weak" or "insufficient" — never report a
                   high-confidence diagnosis for a healthy service.
                3. Distinguish between "what the data shows" and "your hypothesis".
                4. Provide concrete, actionable remediation steps, not generic advice.
                5. Output MUST be valid JSON with the exact fields specified.
                6. EVIDENCE CITATION (MANDATORY), machine-checkable formats only —
                   put every such citation in analysis_detail:
                   a. Metric: quote at least one exact value from the METRICS section as
                      [metric: <name> = <value>]. Copy ONE single "value:" line verbatim —
                      do not round, rewrite, or change its precision. NEVER combine series
                      yourself: do not add, subtract, average, or do any other arithmetic across
                      several returned series to manufacture a total that the query did not
                      return. If a query returns one scalar, quote it; if it returns several
                      series, quote the single one most relevant to THIS alert and describe the
                      rest qualitatively, never as a computed aggregate.
                   b. Log (MANDATORY whenever evidence exists): if the LOGS section contains
                      ANY line matching THIS alert's own fault signature, you MUST quote one
                      such line — citing it is required, not optional. This also holds INSIDE a
                      composite incident where another fault is active: match the line to THIS
                      alert's signature (a latency alert quotes the latency/fault-delay line; an
                      error-rate alert quotes the exception/downstream line) and never skip it
                      merely because a different fault is present at the same time. Quote a SHORT
                      fragment copied VERBATIM (character for character) from a single log line,
                      in this exact double-quoted form:
                      [log: "<verbatim fragment>"] and append its [E{n}] right after it.
                      Do not paraphrase, reword, translate, merge text across lines, or use
                      single quotes — a reworded quote cannot be resolved and is worthless.
                      Good: [log: "Connection is not available, request timed out after 30000ms"] [E6]
                      Bad:  'connection pool errors in the logs' [E6]
                   c. If NO log line matches THIS alert's fault class, state
                      "No logs matching this alert's signature were found" and rely on
                      metric evidence alone — NEVER quote an unrelated ERROR log or invent a
                      fragment just to fill a citation quota. A correct metric-only diagnosis
                      beats one propped up by a fabricated or paraphrased log quote.
                7. CONFIDENCE CALIBRATION: score against THIS alert's PRIMARY signal, and do
                   NOT demand evidence the fault mode does not produce:
                   - FIRST, fix what confidence MEANS: it is the probability that a REAL,
                     actionable incident is occurring RIGHT NOW — it is NOT how certain you are
                     of your own sentence. Concluding "the service is healthy" with great
                     certainty is still a LOW incident confidence.
                   - HEALTHY OVERRIDE (highest priority): if the data is normal — up=1, error
                     rate 0 (or far under threshold), latency/heap within bounds, and no
                     fault-class logs — you MUST output confidence 0.3-0.5. Quoting exact NORMAL
                     values (up=1, error_rate=0) is evidence OF health, never grounds for a high
                     score, regardless of how many such metrics you cite. Do not let the
                     "quote an exact metric" rule inflate a healthy verdict.
                   - 0.7-0.9: the alert's own primary metric is clearly ABNORMAL (breaches its
                     threshold) AND you quote its exact abnormal value. For error-rate / latency
                     a matching fault-class log is ideal. For a resource/heap alert the precise
                     heap-utilization number above threshold is SUFFICIENT ON ITS OWN: a leak
                     pushes heap past the threshold long before it throws OutOfMemoryError, so
                     the ABSENCE of an OutOfMemory log is the expected pre-OOM state and must
                     NOT push confidence below 0.7 when the quoted heap value clearly breaches
                     the threshold.
                   - 0.5-0.6: the primary metric is abnormal but you describe it only
                     qualitatively (e.g. "above 85%" with no exact quoted number), or the
                     evidence is partial / indirect.
                   - 0.2-0.4: weak or indirect evidence; below 0.2: no evidence.
                   evidence_strength must track this: strong = exact abnormal primary value
                   (+ confirming fault-class logs when that fault mode actually emits them),
                   moderate = one quoted or qualitative piece, weak/insufficient = none.
                8. DISCRIMINATION AGAINST CONCURRENT SIGNALS (CRITICAL): The retrieved
                   context MAY contain evidence from OTHER, concurrent incidents — this is
                   expected in production. Your root cause MUST be driven by THIS alert's
                   own metric signature:
                   - HighErrorRate: error rate / 5xx counts drive it; logs identify the
                     exception type.
                   - HighLatency: p99 / avg latency drives it. ONLY latency fault markers
                     in logs (e.g. "sleeping", "Latency fault") may confirm it. ERROR logs
                     about 5xx, connection pool, or downstream timeouts are ALWAYS
                     competing signals for a latency alert — report them in
                     competing_signals_observed and NEVER make them the root cause, even
                     if they plausibly explain the slowness.
                   - HighMemoryUsage: the JVM heap-utilization metric drives it, and its exact
                     value is self-sufficient (a leak breaches the heap threshold before any
                     OutOfMemoryError is logged, so a missing OutOfMemory line is not evidence
                     against the diagnosis). OutOfMemory-style log markers may additionally
                     confirm it when present. 5xx / downstream ERROR logs are ALWAYS competing
                     signals for a memory alert — report them in competing_signals_observed and
                     NEVER make them the root cause.
                   a. ALWAYS quote THIS alert's OWN primary-metric exact value, in every
                      analysis including composites: error-rate → the error-rate ratio,
                      latency → the p99 value, memory → the heap-utilization value. A second
                      fault being active is never a reason to omit this alert's own metric.
                      Identify signals that do NOT match THIS alert's metric signature and
                      report them in competing_signals_observed; do NOT let them become your
                      primary root cause.
                   b. Distinguish two very different kinds of competing signal:
                      - A signal that belongs to a SEPARATE, genuinely-firing concurrent alert
                        (the normal composite case — e.g. a real latency fault while you are
                        scoring the memory alert) is NOT evidence against THIS alert. Do NOT cap
                        THIS alert's confidence because of it: if THIS alert's own primary
                        metric clearly breaches its threshold and you quote its exact value,
                        score it on its own merits (a quoted heap value clearly over threshold
                        stays >= 0.7 regardless of the other fault).
                      - Only cap confidence at 0.5 and set evidence_alignment "conflicting"
                        when a signal is genuinely UNEXPLAINED and you cannot tell whether it
                        belongs to THIS alert or something else — not merely because another
                        confirmed fault is present.
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
                MANDATORY: in analysis_detail, quote at least one exact metric value
                (format: [metric: <name> = <value>]). When you quote a log line, use the exact
                machine-checkable form [log: "<verbatim fragment copied from one log line>"] and
                append its [E{n}]; never use single quotes and never paraphrase the log text.
                If ANY log line matches THIS alert's own fault signature you MUST quote one such
                line — this still applies inside a composite incident, where you match the line
                to THIS alert's signature even while another fault is active. Quote a log ONLY
                when it matches THIS alert's fault class; if none match at all, state that no
                matching logs were found and rely on the metric alone. Analysis without any
                quoted metric will be treated as weak evidence (confidence <= 0.4).
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

            // v13 deterministic backstop: reconcile an internally-inconsistent heap verdict.
            applyHeapConfidenceGuardrail(incident);

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
     * Deterministic confidence guardrail for heap/memory alerts (v13).
     *
     * Prompt calibration is probabilistic: even after quoting a heap-utilization value that
     * clearly breaches the HighMemoryUsage alert threshold ({@code used/max > 0.85}), the
     * model sometimes returns a low confidence (observed 0.4-0.5) — an internally
     * contradictory verdict. The prompt is the soft guide and stays the primary mechanism;
     * this is a narrow, explainable backstop. It only reads a value the MODEL ITSELF cited,
     * and only ever raises a too-low score toward the floor that breached primary evidence
     * warrants — it never lowers a score and can never manufacture evidence.
     */
    private void applyHeapConfidenceGuardrail(Incident incident) {
        try {
            if (!"HighMemoryUsage".equalsIgnoreCase(incident.getAlertname())) {
                return;
            }
            String detail = incident.getAnalysisDetail();
            if (detail == null) {
                return;
            }
            double maxCitedHeap = -1.0;
            Matcher m = HEAP_CITATION.matcher(detail);
            while (m.find()) {
                try {
                    maxCitedHeap = Math.max(maxCitedHeap, Double.parseDouble(m.group(1)));
                } catch (NumberFormatException ignored) {
                    // Skip a non-numeric capture rather than failing analysis.
                }
            }
            if (maxCitedHeap > HEAP_ALERT_THRESHOLD && incident.getConfidence() < HEAP_CONFIDENCE_FLOOR) {
                double original = incident.getConfidence();
                incident.setConfidence(HEAP_CONFIDENCE_FLOOR);
                log.warn("Confidence guardrail: {} cited heap={} (threshold {}) but confidence={}; raised to {}",
                        incident.getAlertname(), maxCitedHeap, HEAP_ALERT_THRESHOLD,
                        original, HEAP_CONFIDENCE_FLOOR);
            }
        } catch (Exception e) {
            // A guardrail must never break the primary analysis path.
            log.warn("Heap confidence guardrail skipped due to error: {}", e.getMessage());
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
