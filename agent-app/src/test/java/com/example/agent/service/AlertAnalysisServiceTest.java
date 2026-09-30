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
 * AlertAnalysisService 单元测试
 *
 * 测试重点：失败降级（graceful degradation / fallback）逻辑
 * - 当 LLM 调用抛异常时（真实踩过的坑：429 insufficient_quota、网络超时）
 * - incident 必须进入 FAILED 状态而不是让系统崩溃
 * - 已收集到的上下文（metrics/logs/runbooks）必须保留，便于人工复查
 *
 * 对应面试点：
 * "How do you handle AI failures?" → 我用单元测试固化降级行为：
 * mock LLM 抛异常，断言 incident 进入 FAILED 且错误信息被记录。
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
     * 手动构造被测对象（不用 @InjectMocks）：
     * 因为 AlertAnalysisService 的构造器会立刻调用 chatClientBuilder.build()，
     * 必须在构造前 stub build()，否则 chatClient 字段会是 null。
     * （@InjectMocks 会在测试方法执行前就 new 对象，那时 stub 还没生效）
     */
    @BeforeEach
    void setUp() {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        alertAnalysisService = new AlertAnalysisService(
                repository, prometheusTool, lokiTool, runbookRetrieval, chatClientBuilder);
    }

    private AlertAnalysisService alertAnalysisService;

    /** 构造一个典型的 HighErrorRate 告警 */
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

    /** mock 三路取证工具：指标、日志、runbook 都正常返回 */
    private void mockToolsReturn(String metrics, String logs, String runbooks) {
        when(prometheusTool.getServiceOverview(anyString())).thenReturn(metrics);
        when(lokiTool.searchLogsBetween(anyString(), anyString(), any(), any(), anyInt(), any()))
                .thenReturn(logs);
        when(runbookRetrieval.retrieve(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(runbooks);
    }

    /** mock ChatClient 链：.call() 抛指定异常（build() 已在 setUp 中 stub） */
    private void mockChatClientChain(RuntimeException failure) {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(failure);
    }

    /** mock ChatClient 链：.call() 正常返回，输出指定 JSON 字符串 */
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
        // 1. 准备（Arrange）：取证正常，LLM 失败（模拟 429 配额耗尽）
        // ============================================================
        mockToolsReturn(
                "http_server_requests_seconds_count 12345",
                "ERROR java.lang.NullPointerException at OrdersController",
                "## High Error Rate Runbook\n1. Check DB connection pool"
        );
        mockChatClientChain(new RuntimeException("Simulated LLM outage: 429 insufficient_quota"));

        // repository.save 在 createAndAnalyze 中被调用两次，都返回传入对象
        when(repository.save(any(Incident.class))).thenAnswer(inv -> inv.getArgument(0));

        // ============================================================
        // 2. 执行（Act）
        // ============================================================
        Incident result = alertAnalysisService.createAndAnalyze(buildIncident());

        // ============================================================
        // 3. 断言（Assert）：降级成功，系统不崩溃
        // ============================================================
        // 3a. 状态进入 FAILED
        assertThat(result.getStatus()).isEqualTo(Incident.AnalysisStatus.FAILED);
        // 3b. 错误信息被记录（含原始异常消息）
        assertThat(result.getErrorMessage()).contains("Simulated LLM outage");
        // 3c. 兜底文案 + 置信度归零（明确告知"需要人工复查"）
        assertThat(result.getRootCauseHypothesis())
                .isEqualTo("Analysis failed - manual review required");
        assertThat(result.getConfidence()).isEqualTo(0.0);
        // 3d. 取证阶段已收集的上下文被保留（人工复查的依据）
        assertThat(result.getRelatedMetrics()).isNotBlank();
        assertThat(result.getRelatedLogs()).isNotBlank();
        assertThat(result.getMatchedRunbooks()).isNotBlank();

        // ============================================================
        // 4. 验证调用顺序（Verification）
        // ============================================================
        // 4a. 三路取证确实发生在 LLM 调用之前
        verify(prometheusTool).getServiceOverview("demo-app");
        verify(lokiTool, atLeast(1)).searchLogsBetween(eq("demo-app"), anyString(), any(), any(), anyInt(), any());
        verify(runbookRetrieval).retrieve(
                "HighErrorRate", "demo-app", "error-rate",
                "Error rate above 50% for 5 minutes");
        // 4b. LLM 只被调用了一次，然后失败
        verify(chatClient, times(1)).prompt();
        // 4c. incident 被保存两次（创建 + 降级后更新）
        verify(repository, times(2)).save(any(Incident.class));
    }

    @Test
    void llmSuccess_shouldParseStructuredOutput_andCompleteIncident() {
        // ============================================================
        // 1. 准备（Arrange）：取证正常，LLM 正常返回结构化 JSON
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
        // 2. 执行（Act）
        // ============================================================
        Incident result = alertAnalysisService.createAndAnalyze(buildIncident());

        // ============================================================
        // 3. 断言（Assert）：成功路径——JSON 被正确解析
        // ============================================================
        // 3a. 状态进入 COMPLETED（不是 FAILED）
        assertThat(result.getStatus()).isEqualTo(Incident.AnalysisStatus.COMPLETED);
        // 3b. 根因假设被解析出来
        assertThat(result.getRootCauseHypothesis())
                .isEqualTo("Orders API throwing RuntimeException due to DB connection pool exhaustion");
        // 3c. 置信度被解析（0.85）
        assertThat(result.getConfidence()).isEqualTo(0.85);
        // 3d. 详细分析 + 建议动作数组 → 字符串
        assertThat(result.getAnalysisDetail()).contains("92%");
        assertThat(result.getSuggestedActions())
                .contains("- Restart DB connection pool")
                .contains("- Increase max pool size");
        // 3e. 没有错误信息，模型被记录
        assertThat(result.getErrorMessage()).isNull();
        assertThat(result.getModelUsed()).isEqualTo("gpt-4o-mini");

        // ============================================================
        // 4. 验证调用顺序（Verification）
        // ============================================================
        // 4a. 三路取证发生在 LLM 调用之前
        verify(prometheusTool).getServiceOverview("demo-app");
        verify(lokiTool, atLeast(1)).searchLogsBetween(eq("demo-app"), anyString(), any(), any(), anyInt(), any());
        verify(runbookRetrieval).retrieve(
                "HighErrorRate", "demo-app", "error-rate",
                "Error rate above 50% for 5 minutes");
        // 4b. LLM 只被调用一次
        verify(chatClient, times(1)).prompt();
        verify(callResponseSpec, times(1)).chatResponse();
        // 4c. incident 被保存两次（创建 + 完成后更新）
        verify(repository, times(2)).save(any(Incident.class));
    }

    @Test
    void llmReturnsInvalidJson_shouldFallbackToRawOutput() {
        // ============================================================
        // 1. 准备：LLM 返回【纯文本】（不是 JSON——真实场景：模型没遵守格式）
        // ============================================================
        mockToolsReturn(
                "http_server_requests_seconds_count 12345",
                "ERROR java.lang.RuntimeException at OrdersController",
                "## High Error Rate Runbook\n1. Check DB connection pool"
        );
        mockChatClientSuccess("Sorry, I cannot analyze this alert right now.");

        when(repository.save(any(Incident.class))).thenAnswer(inv -> inv.getArgument(0));

        // ============================================================
        // 2. 执行
        // ============================================================
        Incident result = alertAnalysisService.createAndAnalyze(buildIncident());

        // ============================================================
        // 3. 断言：这是"第二种失败"——LLM 没抛异常，但输出无法解析
        //    parseAndSaveAnalysis 内部 catch（不是 createAndAnalyze 的 catch），
        //    所以状态仍是 COMPLETED，但内容标记为"解析失败" + 原文保留
        // ============================================================
        assertThat(result.getStatus()).isEqualTo(Incident.AnalysisStatus.COMPLETED);
        assertThat(result.getRootCauseHypothesis())
                .isEqualTo("Failed to parse structured output - see analysis_detail");
        // 原始输出被保存到 analysis_detail（人工可看）
        assertThat(result.getAnalysisDetail())
                .isEqualTo("Sorry, I cannot analyze this alert right now.");
        // 解析失败时的兜底置信度是 0.3
        assertThat(result.getConfidence()).isEqualTo(0.3);
        // 证据仍然保留
        assertThat(result.getRelatedMetrics()).isNotBlank();
        assertThat(result.getRelatedLogs()).isNotBlank();
    }

    @Test
    void llmReturnsJsonWrappedInMarkdown_shouldCleanAndParseSuccessfully() {
        // ============================================================
        // 1. 准备：LLM 返回【markdown 代码块包裹的合法 JSON】
        //    （真实场景：模型喜欢把 JSON 包在 ```json ... ``` 里）
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
        // 2. 执行
        // ============================================================
        Incident result = alertAnalysisService.createAndAnalyze(buildIncident());

        // ============================================================
        // 3. 断言：parseAndSaveAnalysis 第 247-251 行的清理逻辑生效
        //    （去掉 ```json 前缀和 ``` 后缀后再 parse）
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
        // 1. 准备：Loki 返回超长日志（>2000 字符，真实场景：日志刷屏）
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
        // 2. 执行
        // ============================================================
        Incident result = alertAnalysisService.createAndAnalyze(buildIncident());

        // ============================================================
        // 3. 断言：truncate(2000) 生效——超长文本被截断并打标记
        //    （主类第 281-285 行：substring(0, 2000) + "... [truncated]"）
        // ============================================================
        assertThat(result.getRelatedLogs()).endsWith("[truncated]");
        assertThat(result.getRelatedLogs()).hasSize(2015); // 2000 + "... [truncated]"（15字符）
        assertThat(result.getStatus()).isEqualTo(Incident.AnalysisStatus.COMPLETED);
    }

    @Test
    void llmCitationByIndex_shouldResolveToExecutableEvidence() {
        // ============================================================
        // 1. 准备（v9 证据链可执行化）：工具记录了真实执行的查询，
        //    LLM 输出中引用 [E1]（Prometheus）和 [E2]（Loki）
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
        // 2. 执行
        // ============================================================
        Incident result = alertAnalysisService.createAndAnalyze(buildIncident());

        // ============================================================
        // 3. 断言：编号被后端映射成真实查询 + 可点击深链（resolved=true）
        // ============================================================
        assertThat(result.getEvidenceCitations()).contains("\"index\":1");
        assertThat(result.getEvidenceCitations()).contains("\"index\":2");
        assertThat(result.getEvidenceCitations()).contains("rate(http_server_requests_seconds_count");
        assertThat(result.getEvidenceCitations()).contains("{service=\\\"demo-app\\\"} |= `ERROR`");
        assertThat(result.getEvidenceCitations()).contains("explore");
        assertThat(result.getEvidenceCitations()).contains("\"resolved\":true");
        // LLM 没有生成 query——query 来自后端记录（防伪造的核心断言）
        assertThat(result.getEvidenceCitations()).contains("1 series");
        assertThat(result.getEvidenceCitations()).contains("3 matching log lines");
        assertThat(result.getStatus()).isEqualTo(Incident.AnalysisStatus.COMPLETED);
    }

    @Test
    void llmFabricatedCitationIndex_shouldNotResolveToAnyQuery() {
        // ============================================================
        // 1. 准备：LLM 引用了一个 EVIDENCE INDEX 中不存在的编号 [E99]
        //    （真实场景：模型幻觉，编造证据编号）
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
        // 2. 执行
        // ============================================================
        Incident result = alertAnalysisService.createAndAnalyze(buildIncident());

        // ============================================================
        // 3. 断言：伪造编号 resolved=false，且不产生任何 query/url
        //    （v9 安全设计：LLM 无法注入假证据——query/url 只能来自后端）
        // ============================================================
        assertThat(result.getEvidenceCitations()).contains("\"index\":99");
        assertThat(result.getEvidenceCitations()).contains("\"resolved\":false");
        assertThat(result.getEvidenceCitations()).doesNotContain("\"query\"");
        assertThat(result.getEvidenceCitations()).doesNotContain("explore");
    }
}
