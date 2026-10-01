# AI Incident Diagnosis Agent — Testing Strategy

> What this test suite proves, how it tests it, and why it is designed this way.
> Companion code: `agent-app/src/test/java/com/example/agent/service/AlertAnalysisServiceTest.java` (7 tests)
> Chinese version: [docs/TESTING-STRATEGY-zh.md](docs/TESTING-STRATEGY-zh.md)

---

## 1. Why AI systems need a dedicated testing strategy

| Difficulty | Traditional system | AI system (this project's response) |
|---|---|---|
| **Output determinism** | fixed input → fixed output | LLM output is **non-deterministic** → tests must **mock the LLM** and fix its behavior |
| **External dependencies** | DB/API reachable | LLM/Prometheus/Loki/RAG are all external → all mocked: **isolated**, **fast (~1.7s)**, **deterministic** |
| **Failure modes** | single (exception) | **two**: throwing (network / 429) + returning garbage (ignoring the format) → both must be tested |
| **State machine** | simple | `PENDING → ANALYZING → COMPLETED/FAILED` → every path must assert its terminal state |

**Core principle: mock every external dependency; test only the service's own logic (a unit test isolates the service's own logic).**

---

## 2. Test matrix (7 tests = all branches of the main class)

Object under test: `AlertAnalysisService.createAndAnalyze()` (the alert-analysis entry point)

| # | Test | LLM behavior (mock) | Core assertion | Main-class path covered |
|---|---|---|---|---|
| 1 | `llmFailure_shouldMarkIncidentAsFailed_gracefulDegradation` | `.call()` throws `RuntimeException` | status `FAILED`, error recorded, evidence preserved, confidence 0.0 | outer catch: **graceful degradation** |
| 2 | `llmSuccess_shouldParseStructuredOutput_andCompleteIncident` | returns valid structured JSON | status `COMPLETED`, root cause / confidence / actions parsed | success path: **normal parsing** |
| 3 | `llmReturnsInvalidJson_shouldFallbackToRawOutput` | returns plain text (not JSON) | status stays `COMPLETED`, raw text saved to `analysisDetail`, confidence 0.3 | inner catch: **parse tolerance** |
| 4 | `llmReturnsJsonWrappedInMarkdown_shouldCleanAndParseSuccessfully` | returns JSON in a ```json fence | cleaned and parsed (COMPLETED + fields correct) | markdown-cleanup logic: **tolerant parser** |
| 5 | `oversizedContext_shouldBeTruncated_withMarker` | normal + oversized evidence (400 log lines) | `relatedLogs` length 2015, `endsWith("[truncated]")` | `truncate()`: **context protection** |
| 6 | `llmCitationByIndex_shouldResolveToExecutableEvidence` | cites `[E1]` / `[E2]` | `evidenceCitations` resolve to backend queries + deep links, `resolved=true` | v9 citation resolution: **executable evidence** |
| 7 | `llmFabricatedCitationIndex_shouldNotResolveToAnyQuery` | cites non-existent `[E99]` | `resolved=false`, no query/url present | v9 anti-fabrication: **index mapping guard** |

**Two success/failure paths (tests 1&2) + two parser boundaries (tests 3&4) + one data-protection (test 5) + two v9 citation guards (tests 6&7) = every testable branch of the main class is covered.**

---

## 3. Mockito techniques (each one is a real pitfall we hit)

### Technique 1: the `@InjectMocks` ordering trap (setUp)
`AlertAnalysisService`'s constructor **immediately calls** `chatClientBuilder.build()`. `@InjectMocks` instantiates the object before stubs take effect → `chatClient` would be null.
**Fix**: in `@BeforeEach`, stub `when(chatClientBuilder.build()).thenReturn(chatClient)` *first*, then construct manually with `new AlertAnalysisService(...)`.
→ Interview point: *stubbing vs instantiation order*.

### Technique 2: mock every link of the fluent chain
The main class calls `chatClient.prompt().system(...).user(...).call().chatResponse()` — a fluent chain.
**Fix**: each link returns the next one: `requestSpec → callResponseSpec → chatResponse → generation → assistantMessage`; the innermost `getContent()` returns the fake LLM output.
→ Interview point: *mocking fluent/chained APIs*.

### Technique 3: `thenAnswer` preserves the method contract
The main class continues working on the return value of `incident = repository.save(incident)`. A mock returning null → NPE.
**Fix**: `when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0))` — return the passed object as-is, matching the real `save` contract.
→ Interview point: *a mock's return value must honor the method contract, or the test itself is wrong*.

### Technique 4: broad matchers in `when`, exact args in `verify`
- **Arrange** with `anyString()` / `anyInt()`: return the stubbed value regardless of input
- **Verify** with exact arguments `getServiceOverview("demo-app")`, `searchLogsBetween("demo-app", ...)`: proves the code actually called with those values
→ Interview point: *verify with exact arguments proves call ordering and wiring*.

---

## 4. Interview narrative (English, ready to speak)

**Opening (Apple-style: what → why → how)**
> *"I test my AI system like any other critical service — because an AI that fails silently is worse than one that crashes."*
> *"I wrote 7 unit tests covering every branch of my analysis pipeline: LLM outages, malformed outputs, oversized contexts, and citation integrity."*
> *"The tests mock the LLM and external tools with Mockito, so they're deterministic, run in ~1.7s, and lock in my graceful-degradation contract."*

**Three points worth digging into**
1. **Two failure modes**: *"An LLM can fail in two ways — throw an exception or return garbage. My tests cover both: exception → FAILED with preserved evidence; garbage → COMPLETED with confidence 0.3 and raw output saved for human review."*
2. **Paired tests**: *"I test happy path and failure path in pairs — success parsing vs graceful degradation — so the contract is locked on both ends."*
3. **Tests caught my own bug**: *"One of my tests caught a bug in my assertion — I miscounted the truncation-marker length (2017 vs actual 2015). The test proved me wrong, which is exactly what tests are for."*

---

## 5. Roadmap

| Layer | Content | Tooling |
|---|---|---|
| **Unit tests (current)** | all paths of `AlertAnalysisService` | JUnit 5 + Mockito + AssertJ |
| **Tool-service tests (next)** | HTTP calls of `PrometheusToolService` / `LokiToolService` (URL construction, encoding, errors) | `MockRestServiceServer` (Spring, mocks the HTTP layer) |
| **RAG tests** | `RunbookRetrievalService` keyword scoring + ranking | temp runbook dir + assert top results |
| **Integration tests** | real DB reads/writes (Testcontainers Postgres), webhook end-to-end (POST → persisted) | Testcontainers / `@SpringBootTest` |
| **Real-LLM smoke** | unmocked real call (manual / CI-tagged), verifies prompt effectiveness and cost | manual + token-audit fields |

---

## 6. Numbers at a glance (for resume / interviews)

- **7 unit tests** covering every testable branch of `AlertAnalysisService`
- Runtime **~1.7s** (no network, no DB, no real LLM call)
- Object under test: `AlertAnalysisService` (core orchestration logic)
- Stack: JUnit 5 + Mockito + AssertJ (Java 17 / Maven Surefire)
