# AI Incident Triage Agent — 测试策略（Testing Strategy）

> 面试用途文档：这套测试证明了什么、怎么测的、为什么这样测。
> 配套代码：`agent-app/src/test/java/com/example/agent/service/AlertAnalysisServiceTest.java`（5 个测试）

---

## 1. 为什么 AI 系统需要专门的测试策略

| 难点 | 传统系统 | AI 系统（本项目的应对） |
|---|---|---|
| **输出确定性** | 输入固定 → 输出固定 | LLM 输出**非确定**（non-deterministic）→ 测试必须 **mock LLM**，固定它的行为 |
| **外部依赖** | DB/API 可连 | LLM/Prometheus/Loki/RAG 全是外部 → 全 mock，测试**隔离（isolated）**、**快（~1.7s）**、**可复现（deterministic）** |
| **失败模式** | 单一（异常） | **两种**：抛异常（网络/429）+ 返回垃圾（不遵守格式）→ 两种都要测 |
| **状态机** | 简单 | `PENDING → ANALYZING → COMPLETED/FAILED` → 每条路径都要断言终态 |

**核心原则：mock 一切外部依赖，只测被测对象自己的逻辑（unit test isolates the service's own logic）。**

---

## 2. 测试矩阵（5 个测试 = 主类全部分支）

被测对象：`AlertAnalysisService.createAndAnalyze()`（告警分析主入口）

| # | 测试名 | LLM 行为（mock） | 断言的核心 | 覆盖的主类路径/分支 |
|---|---|---|---|---|
| 1 | `llmFailure_shouldMarkIncidentAsFailed_gracefulDegradation` | `.call()` 抛 `RuntimeException` | 状态 `FAILED`、错误信息保留、取证上下文保留、confidence 0.0 | 外层 catch（L85-92）：**失败降级** |
| 2 | `llmSuccess_shouldParseStructuredOutput_andCompleteIncident` | 返回合法结构化 JSON | 状态 `COMPLETED`、根因/置信度/actions 正确解析 | 成功路径（L143-167）：**正常解析** |
| 3 | `llmReturnsInvalidJson_shouldFallbackToRawOutput` | 返回纯文本（非 JSON） | 状态仍 `COMPLETED`、原文存 `analysisDetail`、confidence 0.3 | 内层 catch（L272-278）：**解析容错** |
| 4 | `llmReturnsJsonWrappedInMarkdown_shouldCleanAndParseSuccessfully` | 返回 ```json 包裹的 JSON | 清理后仍解析成功（COMPLETED + 字段正确） | markdown 清理逻辑（L247-251）：**容错解析器** |
| 5 | `oversizedContext_shouldBeTruncated_withMarker` | 正常 + 取证文本超长（400 行日志） | `relatedLogs` 长度 2015、`endsWith("[truncated]")` | `truncate()`（L281-285）：**上下文保护** |

**两条成功/失败路径（测试1&2）+ 两个解析器边界（测试3&4）+ 一个数据保护（测试5）= 主类所有可测分支全覆盖。**

---

## 3. Mockito 关键技巧（每个都是真实踩坑记录）

### 技巧 1：`@InjectMocks` 时序坑（setUp，L82-87）
`AlertAnalysisService` 构造器**立刻调用** `chatClientBuilder.build()`（主类 L63）。`@InjectMocks` 在 stub 生效前就 new 对象 → `chatClient` 为 null。
**解决**：`@BeforeEach` 先 `when(chatClientBuilder.build()).thenReturn(chatClient)`，再手动 `new AlertAnalysisService(...)`。
→ 面试点：*stubbing vs instantiation order*。

### 技巧 2：链式调用逐环 mock（L122-131）
主类 `chatClient.prompt().system(...).user(...).call().chatResponse()` 是链式调用。
**解决**：每环都 mock 成"返回下一环"：`requestSpec → callResponseSpec → chatResponse → generation → assistantMessage`，最深处的 `getContent()` 返回假 LLM 输出。
→ 面试点：*mocking fluent/chained API*。

### 技巧 3：`thenAnswer` 保持方法契约（L146）
主类 `incident = repository.save(incident)` 用返回值继续操作。mock 若返回 null → NPE。
**解决**：`when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0))`——原样返回传入对象，符合真实 save 的契约。
→ 面试点：*mock 返回值必须符合方法契约，否则测试本身是错的*。

### 技巧 4：`when` 用宽匹配器，`verify` 用精确参数（L173-177）
- **Arrange** 用 `anyString()/anyInt()`：随便传什么都返回这些值
- **Verify** 用精确参数 `getServiceOverview("demo-app")`、`getRecentErrors("demo-app", 15)`：**证明代码确实用这些值调用了**
→ 面试点：*verify with exact arguments proves call ordering and wiring*。

---

## 4. 面试叙事（英文模板，可直接说）

**开场（苹果式：小学生能懂 → 本质 → 技术细节）**
> *"I test my AI system like any other critical service — because an AI that fails silently is worse than one that crashes."*
> *"I wrote 5 unit tests covering every branch of my analysis pipeline: LLM outages, malformed outputs, and oversized contexts."*
> *"The tests mock the LLM and external tools with Mockito, so they're deterministic, run in ~1.7s, and lock in my graceful degradation contract."*

**三个可深挖的点**
1. **两种失败模式**：*"An LLM can fail in two ways — throw an exception or return garbage. My tests cover both: exception → FAILED with preserved evidence; garbage → COMPLETED with confidence 0.3 and raw output saved for human review."*
2. **配对测试**：*"I test happy path and failure path in pairs — success parsing vs graceful degradation — so the contract is locked on both ends."*
3. **测试抓到我的错**：*"One of my own tests caught a bug in my assertion — I miscounted the truncation marker length (2017 vs actual 2015). The test proved me wrong, which is exactly what tests are for."*

---

## 5. 未来扩展（Roadmap）

| 层 | 内容 | 工具 |
|---|---|---|
| **单元测试（当前）** | `AlertAnalysisService` 全部路径 | JUnit 5 + Mockito + AssertJ |
| **工具服务测试（下一步）** | `PrometheusToolService`/`LokiToolService` 的 HTTP 调用（URL 构造、编码、异常） | `MockRestServiceServer`（Spring 自带，mock HTTP 层） |
| **RAG 测试** | `RunbookRetrievalService` 关键词评分排序 | 临时 runbook 目录 + 断言 top 结果 |
| **集成测试** | DB 真实读写（Testcontainers 起 Postgres）、webhook 端到端（POST → 入库） | Testcontainers / `@SpringBootTest` |
| **真实 LLM 冒烟** | 不 mock 的真实调用（手动/CI 标签），验证 prompt 效果和 cost | 手动 + token 审计字段 |

---

## 6. 数字速查（写进简历/面试）

- 5 个单元测试，覆盖 `AlertAnalysisService` 全部可测分支
- 测试运行时间 **~1.7s**（无网络、无 DB、无 LLM 真实调用）
- 被测类：`AlertAnalysisService`（287 行核心编排逻辑）
- 测试框架：JUnit 5 + Mockito + AssertJ（Java 17 / Maven Surefire 3.2.5）
