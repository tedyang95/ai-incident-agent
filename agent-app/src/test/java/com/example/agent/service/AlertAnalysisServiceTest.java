package com.example.agent.service;

import com.example.agent.evidence.QueryRecord;
import com.example.agent.model.Incident;
import com.example.agent.rag.RunbookRetrievalService;
import com.example.agent.repository.IncidentRepository;
import com.example.agent.tool.LokiToolService;
import com.example.agent.tool.PrometheusToolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for AlertAnalysisService.
 * <p>
 * Focus: graceful-degradation behavior — when the LLM call throws (a real-world
 * failure mode: 429 insufficient_quota, network timeouts), the incident must
 * enter FAILED instead of crashing the system, and the context collected so far
 * must be preserved for manual review.
 * <p>
 * All external dependencies are mocked with Mockito: deterministic, isolated,
 * ~1.7s runtime.
 */
@ExtendWith(MockitoExtension.class)
class AlertAnalysisServiceTest {

    @Mock
    private IncidentRepository repository;

    @Mock
    private PrometheusToolService prometheusTool;

    @Mock
    private LokiToolService lokiTool;

    @Mock
    private RunbookRetrievalService runbookRetrieval;

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    @Mock
    private ChatResponse chatResponse;

    @Mock
    private Generation generation;

    @Mock
    private AssistantMessage assistantMessage;

    /**
     * Constructs the service under test manually (no @InjectMocks):
     * the AlertAnalysisService constructor calls chatClientBuilder.build()
     * immediately, so build() must be stubbed before construction — otherwise
     * chatClient would be null. (@InjectMocks instantiates the object before
     * any stubs take effect.)
     */
    @BeforeEach
    void setUp() {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        alertAnalysisService = new AlertAnalysisService(
                repository, prometheusTool, lokiTool, runbookRetrieval, chatClientBuilder);
    }

    private AlertAnalysisService alertAnalysisService;

    /** Builds a typical HighErrorRate alert. */
    private Incident buildIncident() {
        Incident incident = new Incident();
        incident.setAlertname("HighErrorRate");
        incident.setSeverity("critical");
        incident.setService("demo-app");
        incident.setCategory("error-rate");
        incident.setDescription("Error rate above 50% for 5 minutes");
        incident.setReceivedAt(LocalDateTime.now());
        incident.setStatus(Incident.AnalysisStatus.PENDING);
        return incident;
    }

    /** Mocks the three evidence-gathering tools: metrics, logs, runbooks all return normally. */
    private void mockToolsReturn(String metrics, String logs, String runbooks) {
        when(prometheusTool.getServiceOverview(anyString())).thenReturn(metrics);
        when(lokiTool.searchLogsBetween(anyString(), anyString(), any(), any(), anyInt(), any()))
                .thenReturn(logs);
        when(runbookRetrieval.retrieve(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(runbooks);
    }

    /** Mocks the ChatClient chain: .call() throws the given failure (build() is stubbed in setUp). */
    private void mockChatClientChain(RuntimeException failure) {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(failure);
    }

    /** Mocks the ChatClient chain: .call() returns normally with the given JSON string. */
    private void mockChatClientSuccess(String llmJson) {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(assistantMessage);
        when(assistantMessage.getContent()).thenReturn(llmJson);
    }

    @Test
    void llmFailure_shouldMarkIncidentAsFailed_gracefulDegradation() {
        // ============================================================
        // 1. Arrange: tools return normally, LLM fails (simulated 429 quota)
        // ============================================================
        mockToolsReturn(
                "http_server_requests_seconds_count 12345",
                "ERROR java.lang.NullPointerException at OrdersController",
                "## High Error Rate Runbook\n1. Check DB connection pool"
        );
        mockChatClientChain(new RuntimeException("Simulated LLM outage: 429 insufficient_quota"));

        // repository.save is called twice in createAndAnalyze; both return the passed object.
        when(repository.save(any(Incident.class))).thenAnswer(inv -> inv.getArgument(0));

        // ============================================================
        // 2. Act
        // ============================================================
        Incident result = alertAnalysisService.createAndAnalyze(buildIncident());

        // ============================================================
        // 3. Assert: graceful degradation — the system does not crash
        // ============================================================
        // 3a. Status transitions to FAILED
        assertThat(result.getStatus()).isEqualTo(Incident.AnalysisStatus.FAILED);
        // 3b. The error message is recorded (with the original exception text)
        assertThat(result.getErrorMessage()).contains("Simulated LLM outage");
        // 3c. Fallback copy + confidence reset to zero (explicit "manual review required")
        assertThat(result.getRootCauseHypothesis())
                .isEqualTo("Analysis failed - manual review required");
        assertThat(result.getConfidence()).isEqualTo(0.0);
        // 3d. Context collected before the failure is preserved (basis for manual review)
        assertThat(result.getRelatedMetrics()).isNotBlank();
        assertThat(result.getRelatedLogs()).isNotBlank();
        assertThat(result.getMatchedRunbooks()).isNotBlank();

        // ============================================================
        // 4. Verify call ordering (verification)
        // ============================================================
        // 4a. Evidence gathering happened before the LLM call
        verify(prometheusTool).getServiceOverview("demo-app");
        verify(lokiTool, atLeast(1)).searchLogsBetween(eq("demo-app"), anyString(), any(), any(), anyInt(), any());
        verify(runbookRetrieval).retrieve(
                "HighErrorRate", "demo-app", "error-rate",
                "Error rate above 50% for 5 minutes");
        // 4b. The LLM was called exactly once, then failed
        verify(chatClient, times(1)).prompt();
        // 4c. The incident was saved twice (create + post-degradation update)
        verify(repository, times(2)).save(any(Incident.class));
    }

    @Test
    void llmSuccess_shouldParseStructuredOutput_andCompleteIncident() {
        // ============================================================
        // 1. Arrange: tools return normally, LLM returns valid structured JSON
        // ============================================================
        mockToolsReturn(
                "http_server_requests_seconds_count 12345",
                "ERROR java.lang.RuntimeException at OrdersController",
                "## High Error Rate Runbook\n1. Check DB connection pool"
        );
        mockChatClientSuccess("""
                {
                  "root_cause_hypothesis": "Orders API throwing RuntimeException due to DB connection pool exhaustion",
                  "analysis_detail": "Error rate spiked to 92% after connection pool max reached",
                  "suggested_actions": [
                    "Restart DB connection pool",
                    "Increase max pool size",
                    "Add retry with exponential backoff"
                  ],
                  "confidence": 0.85,
                  "evidence_strength": "strong"
                }
                """);

        when(repository.save(any(Incident.class))).thenAnswer(inv -> inv.getArgument(0));

        // ============================================================
        // 2. Act
        // ============================================================
        Incident result = alertAnalysisService.createAndAnalyze(buildIncident());

        // ============================================================
        // 3. Assert: success path — the JSON is parsed correctly
        // ============================================================
        // 3a. Status transitions to COMPLETED (not FAILED)
        assertThat(result.getStatus()).isEqualTo(Incident.AnalysisStatus.COMPLETED);
        // 3b. The root-cause hypothesis is parsed
        assertThat(result.getRootCauseHypothesis())
                .isEqualTo("Orders API throwing RuntimeException due to DB connection pool exhaustion");
        // 3c. The confidence is parsed (0.85)
        assertThat(result.getConfidence()).isEqualTo(0.85);
        // 3d. Detailed analysis + suggested-actions array → string
        assertThat(result.getAnalysisDetail()).contains("92%");
        assertThat(result.getSuggestedActions())
                .contains("- Restart DB connection pool")
                .contains("- Increase max pool size");
        // 3e. No error, model recorded
        assertThat(result.getErrorMessage()).isNull();
        assertThat(result.getModelUsed()).isEqualTo("gpt-4o-mini");

        // ============================================================
        // 4. Verify call ordering (verification)
        // ============================================================
        // 4a. Evidence gathering happened before the LLM call
        verify(prometheusTool).getServiceOverview("demo-app");
        verify(lokiTool, atLeast(1)).searchLogsBetween(eq("demo-app"), anyString(), any(), any(), anyInt(), any());
        verify(runbookRetrieval).retrieve(
                "HighErrorRate", "demo-app", "error-rate",
                "Error rate above 50% for 5 minutes");
        // 4b. The LLM was called exactly once
        verify(chatClient, times(1)).prompt();
        verify(callResponseSpec, times(1)).chatResponse();
        // 4c. The incident was saved twice (create + completion update)
        verify(repository, times(2)).save(any(Incident.class));
    }

    @Test
    void llmReturnsInvalidJson_shouldFallbackToRawOutput() {
        // ============================================================
        // 1. Arrange: the LLM returns plain text (not JSON — the model ignored the format)
        // ============================================================
        mockToolsReturn(
                "http_server_requests_seconds_count 12345",
                "ERROR java.lang.RuntimeException at OrdersController",
                "## High Error Rate Runbook\n1. Check DB connection pool"
        );
        mockChatClientSuccess("Sorry, I cannot analyze this alert right now.");

        when(repository.save(any(Incident.class))).thenAnswer(inv -> inv.getArgument(0));

        // ============================================================
        // 2. Act
        // ============================================================
        Incident result = alertAnalysisService.createAndAnalyze(buildIncident());

        // ============================================================
        // 3. Assert: this is the "second failure mode" — the LLM did not throw,
        //    but its output is unparseable. parseAndSaveAnalysis catches internally
        //    (not createAndAnalyze's catch), so status stays COMPLETED but content
        //    is marked as "parse failed" and the raw output is preserved.
        // ============================================================
        assertThat(result.getStatus()).isEqualTo(Incident.AnalysisStatus.COMPLETED);
        assertThat(result.getRootCauseHypothesis())
                .isEqualTo("Failed to parse structured output - see analysis_detail");
        // Raw output preserved in analysis_detail (for human review)
        assertThat(result.getAnalysisDetail())
                .isEqualTo("Sorry, I cannot analyze this alert right now.");
        // Fallback confidence for unparseable output is 0.3
        assertThat(result.getConfidence()).isEqualTo(0.3);
        // Evidence is still preserved
        assertThat(result.getRelatedMetrics()).isNotBlank();
        assertThat(result.getRelatedLogs()).isNotBlank();
    }

    @Test
    void llmReturnsJsonWrappedInMarkdown_shouldCleanAndParseSuccessfully() {
        // ============================================================
        // 1. Arrange: the LLM returns valid JSON wrapped in a markdown code fence
        //    (a real-world habit: models like to wrap JSON in ```json ... ```)
        // ============================================================
        mockToolsReturn(
                "http_server_requests_seconds_count 12345",
                "ERROR java.lang.RuntimeException at OrdersController",
                "## High Error Rate Runbook\n1. Check DB connection pool"
        );
        mockChatClientSuccess("""
                ```json
                {
                  "root_cause_hypothesis": "DB connection pool exhausted",
                  "analysis_detail": "Pool size too small for traffic spike",
                  "suggested_actions": ["Increase pool size", "Add autoscaling"],
                  "confidence": 0.9,
                  "evidence_strength": "strong"
                }
                ```
                """);

        when(repository.save(any(Incident.class))).thenAnswer(inv -> inv.getArgument(0));

        // ============================================================
        // 2. Act
        // ============================================================
        Incident result = alertAnalysisService.createAndAnalyze(buildIncident());

        // ============================================================
        // 3. Assert: the markdown-fence cleaning in parseAndSaveAnalysis works
        //    (strips the ```json prefix and ``` suffix before parsing)
        // ============================================================
        assertThat(result.getStatus()).isEqualTo(Incident.AnalysisStatus.COMPLETED);
        assertThat(result.getRootCauseHypothesis()).isEqualTo("DB connection pool exhausted");
        assertThat(result.getConfidence()).isEqualTo(0.9);
        assertThat(result.getSuggestedActions())
                .contains("- Increase pool size")
                .contains("- Add autoscaling");
        assertThat(result.getErrorMessage()).isNull();
    }

    @Test
    void oversizedContext_shouldBeTruncated_withMarker() {
        // ============================================================
        // 1. Arrange: Loki returns oversized logs (>2000 chars; real-world: log spam)
        // ============================================================
        String longLogs = "ERROR java.lang.RuntimeException at OrdersController\n".repeat(400);
        mockToolsReturn(
                "http_server_requests_seconds_count 12345",
                longLogs,
                "## High Error Rate Runbook\n1. Check DB connection pool"
        );
        mockChatClientSuccess("""
                {"root_cause_hypothesis": "DB pool exhausted", "analysis_detail": "x",
                 "suggested_actions": ["Fix"], "confidence": 0.7, "evidence_strength": "strong"}
                """);

        when(repository.save(any(Incident.class))).thenAnswer(inv -> inv.getArgument(0));

        // ============================================================
        // 2. Act
        // ============================================================
        Incident result = alertAnalysisService.createAndAnalyze(buildIncident());

        // ============================================================
        // 3. Assert: truncate(2000) kicks in — the oversized text is cut with a marker
        // ============================================================
        assertThat(result.getRelatedLogs()).endsWith("[truncated]");
        assertThat(result.getRelatedLogs()).hasSize(2015); // 2000 + "... [truncated]" (15 chars)
        assertThat(result.getStatus()).isEqualTo(Incident.AnalysisStatus.COMPLETED);
    }

    @Test
    void llmCitationByIndex_shouldResolveToExecutableEvidence() {
        // ============================================================
        // 1. Arrange (v9 executable citations): tools recorded the queries they
        //    really ran; the LLM cites [E1] (Prometheus) and [E2] (Loki)
        // ============================================================
        mockToolsReturn(
                "http_server_requests_seconds_count 12345",
                "ERROR java.lang.RuntimeException at OrdersController",
                "## High Error Rate Runbook\n1. Check DB connection pool"
        );
        QueryRecord promRecord = new QueryRecord(
                "PROMETHEUS",
                "rate(http_server_requests_seconds_count{job='demo-app'}[5m])",
                "1 series", null, null);
        QueryRecord lokiRecord = new QueryRecord(
                "LOKI",
                "{service=\"demo-app\"} |= `ERROR` != `RuntimeException`",
                "3 matching log lines",
                Instant.now().minus(2, java.time.temporal.ChronoUnit.MINUTES), Instant.now());
        when(prometheusTool.getLastQueryRecords()).thenReturn(List.of(promRecord));
        when(lokiTool.getLastQueryRecord()).thenReturn(lokiRecord);

        mockChatClientSuccess("""
                {
                  "root_cause_hypothesis": "Error rate spiked [E1] while logs show DB timeouts [E2]",
                  "analysis_detail": "Metric [E1] confirms 5xx spike; logs [E2] show connection timeout",
                  "suggested_actions": ["Restart pool"],
                  "confidence": 0.85,
                  "evidence_strength": "strong"
                }
                """);
        when(repository.save(any(Incident.class))).thenAnswer(inv -> inv.getArgument(0));

        // ============================================================
        // 2. Act
        // ============================================================
        Incident result = alertAnalysisService.createAndAnalyze(buildIncident());

        // ============================================================
        // 3. Assert: indexes resolve to the real queries + clickable deep links
        // ============================================================
        assertThat(result.getEvidenceCitations()).contains("\"index\":1");
        assertThat(result.getEvidenceCitations()).contains("\"index\":2");
        assertThat(result.getEvidenceCitations()).contains("rate(http_server_requests_seconds_count");
        assertThat(result.getEvidenceCitations()).contains("{service=\\\"demo-app\\\"} |= `ERROR`");
        assertThat(result.getEvidenceCitations()).contains("explore");
        assertThat(result.getEvidenceCitations()).contains("\"resolved\":true");
        // The LLM did not generate the query — it comes from the backend records
        assertThat(result.getEvidenceCitations()).contains("1 series");
        assertThat(result.getEvidenceCitations()).contains("3 matching log lines");
        assertThat(result.getStatus()).isEqualTo(Incident.AnalysisStatus.COMPLETED);
    }

    @Test
    void llmFabricatedCitationIndex_shouldNotResolveToAnyQuery() {
        // ============================================================
        // 1. Arrange: the LLM cites an index that does not exist in the
        //    EVIDENCE INDEX ([E99] — a hallucinated evidence reference)
        // ============================================================
        mockToolsReturn(
                "http_server_requests_seconds_count 12345",
                "ERROR java.lang.RuntimeException at OrdersController",
                "## High Error Rate Runbook\n1. Check DB connection pool"
        );
        when(prometheusTool.getLastQueryRecords()).thenReturn(List.of());
        when(lokiTool.getLastQueryRecord()).thenReturn(null);

        mockChatClientSuccess("""
                {
                  "root_cause_hypothesis": "Memory leak detected per [E99]",
                  "analysis_detail": "JVM heap grew according to [E99]",
                  "suggested_actions": ["Fix"],
                  "confidence": 0.9,
                  "evidence_strength": "strong"
                }
                """);
        when(repository.save(any(Incident.class))).thenAnswer(inv -> inv.getArgument(0));

        // ============================================================
        // 2. Act
        // ============================================================
        Incident result = alertAnalysisService.createAndAnalyze(buildIncident());

        // ============================================================
        // 3. Assert: a fabricated index resolves to nothing (resolved=false) and
        //    carries no query/url — the LLM cannot inject fake evidence
        // ============================================================
        assertThat(result.getEvidenceCitations()).contains("\"index\":99");
        assertThat(result.getEvidenceCitations()).contains("\"resolved\":false");
        assertThat(result.getEvidenceCitations()).doesNotContain("\"query\"");
        assertThat(result.getEvidenceCitations()).doesNotContain("explore");
    }
}
