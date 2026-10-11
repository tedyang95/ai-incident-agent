# AI Incident Diagnosis Agent — Tuning Log (v1 → v13)

> How this AI system was iteratively tuned toward accurate diagnosis: what changed each round, what was observed, and why it was changed.
> Companion tooling: `docs/eval/run_baseline.sh` (evaluation), `docs/eval/check_grounding.py` (citation-veracity checker)
> Data: `docs/eval/baseline_results_{v1..v13}.csv`, plus the PostgreSQL `incidents` table (every raw analysis is queryable by id)
> Chinese version: [docs/TUNING-zh.md](docs/TUNING-zh.md)

---

## 0. Methodology: why AI systems need tuning

**Evaluation-driven development** — we do not train the model; we measure the *system's reasoning behavior*.

| Concept | How this project applies it |
|---|---|
| **Ground truth** | Inject known faults (error / latency / memory) → the correct answer is *known* → auto-scoring. **Measure, not train.** |
| **Eval set** | `docs/eval/eval-cases.json` — **12 scenarios / 19 scored alerts**: 4 single faults (error / latency / memory / downstream), 5 concurrent dual-faults (10 alerts), 2 time-overlapping *staggered* faults (4 alerts), and 1 healthy control. Each alert has per-alertname ground truth |
| **Eval script** | `run_baseline.sh`: inject fault → load traffic → wait for AI analysis → pull Postgres → write CSV |
| **Isolation (settle)** | Wait for alerts to clear + 30s before each case, so one case's "dirty data" never pollutes the next |
| **Grounding check** | `check_grounding.py`: verifies verbatim that every `[metric:]` / `[log:]` citation exists in the retrieved context |

**Core position**: *"Evaluation should be like a grading rubric — fixed fault injections with known answers; verify not just whether the diagnosis is right, but whether the cited evidence actually exists."*

---

## 1. The fault scenarios (ground truth)

| Fault | Injection | Expected alert | Expected root-cause keywords |
|---|---|---|---|
| error | POST /api/orders returns 500 | HighErrorRate | connection-pool exhaustion, SQLTransientConnectionException |
| latency | all requests delayed 2-5s | HighLatency | p99 latency, delay |
| memory | memory leak ~10MB/s | HighMemoryUsage | heap memory, jvm |
| downstream | dependency (payment service) times out → 502 | HighErrorRate | downstream timeout, 502 (a *different* root cause than `error`) |
| **healthy control** | synthetic alert, **no fault injected** | HealthyCheck | "no anomaly detected", confidence ≤ 0.5 (a correct *non*-incident) |

Scenarios are also combined two ways: **concurrent** (two faults injected at once → two independent alerts) and **staggered** (fault A is stopped and fault B fired while A's logs are still inside the Loki window — a timing-isolation test).

---

## 2. Per-round tuning log

### v1 — First baseline
- **Change**: none (initial system)
- **Result**: error hit (0.7); **latency mis-diagnosed as error** (stale HighErrorRate alert analyzed first); **memory mis-diagnosed as latency** (stale HighLatency alert analyzed first)
- **Finding**: **cross-case pollution** — the previous case's alert had not resolved before the next one was injected
- **Fix**: settle isolation (poll Prometheus for no firing alerts + 30s window before each case)
- **Lesson**: an evaluation environment must be isolated, or you are "measuring the tail of the previous run"

### v2 — Settle fix + citation prompt
- **Change**: settle isolation in effect; system prompt enforces citation rules (`[metric: ...]` / `[log: "..."]`)
- **Result**: **3/3 hit** (error 0.7 / latency 0.6 / memory 0.7)
- **Finding (post-mortem)**: ⚠️ **every citation was fabricated** — the raw `related_logs` in Postgres were all `"Loki search error: 400 Bad Request ... invalid char literal"`. The log lines the LLM quoted did **not** exist in the context.
- **Lesson**: **format compliance ≠ grounded**. A 100% hit rate can be hiding a broken evidence chain since day one.

### v3 — Window 15min→5min + temperature 0.3→0.1 (❌ two variables)
- **Change**: two variables at once (shorter window + lower temperature)
- **Result**: error 0.8 / memory 0.7 stable; **latency 0.8→0.6 regression** (root cause drifted to "database queries")
- **Lesson**: **change one variable at a time** — a regression cannot be attributed otherwise

### v4 — Rerun (❌ fake fix)
- **Change**: none (assumed Loki was fixed)
- **Result**: **still all 400s** — the fix never reached the container in use
- **Lesson**: **after any code change, verify end to end (build → run → check real output)**; trust the built artifact, not memory

### v5 — 🎯 Fixed the LogQL single-quote bug (the turning point)
- **Change**: `{service='demo-app'}` → `{service="demo-app"}` (Loki LogQL only accepts double quotes)
- **Result**: **HTTP 400→200, streams 0→1**; 3/3 hit; **citations went from 0% real → 100% verbatim real** (metric 3/3, log 2/2, verified by the grounding checker)
- **Finding**: this bug had been present **since day one** — log retrieval silently failed through v1/v2/v3 while the metrics path worked, so the hit rate always "looked fine"
- **Lesson**: **one character can break your whole evidence chain**; evaluation must verify *that the evidence exists*, not just the output format

### v6 — Correlation window (anchor the log window to the alert time)
- **Change**: Loki queries moved from `now - 5min` (sliding window) to **`[alert.receivedAt - 2min, alert.receivedAt]`** (correlation window)
- **Result**: error **0.8→0.9** (contamination gone); latency **0.5** (no ERROR logs in window → correctly calibrated lower confidence)
- **Finding**: a sliding window drags in stale logs from the previous case (v5 latency quoted the error case's "database connection timeout" — real but *irrelevant*)
- **Lesson**: **root causes happen before the alert — query the window before the alert, not "now"**; and correctly lowering confidence when evidence is absent is good behavior

### v7b — Alert-aware retrieval (tool signature adapts to the task)
- **Change**: retrieval keywords are driven by the **alert category** — a latency alert also searches `sleeping` / `Latency fault` / `timeout` (latency faults leave WARN/DEBUG logs, not stack traces)
- **Result**: latency **0.5→0.9**! The LLM quoted the injection log verbatim: `[log: "FAULT INJECTION: Latency fault ENABLED - all requests will have 2-5s delay"]`
- **Result**: **3/3 hit** (error 0.8 / latency 0.9 / memory 0.7), **grounding 100%** (metric 3/3, log 2/2)
- **Lesson**: **a tool's signature (here: search keywords) should adapt to task semantics** — searching only ERROR misses the entire latency case

### v8 — Composite faults 3×2: retrieval isolation + discrimination rules
- **Context**: eval set expanded to 3 composite cases (error+latency / latency+memory / error+memory) — two alerts delivered independently, each Incident must hit its own root cause. The first composite baseline exposed: **HighLatency was polluted by the concurrent error's stack traces and confidently mis-diagnosed as "database connection timeouts + RuntimeException" (confidence 0.8)**. Mechanism: the latency keyword "timeout" matched the error's exception message "database connection timeout (simulated)", dragging the stack traces into the latency context.
- **Change (root fix)**:
  - latency keywords dropped generic `timeout`, keeping only fault-injection markers (`sleeping` / `Latency fault`)
  - non-error-rate alerts **exclude `!= "RuntimeException"` at the LogQL layer** (concurrent error stack traces never enter this alert's context)
- **Change (safety net)**: system prompt rules 8/9 — **discrimination instructions** (evidence must match THIS alert's metric signature: error-rate↔error rate / latency↔p99 / memory↔heap; competing signals go to `competing_signals_observed`, never the primary root cause; confidence capped at 0.5 when they cannot be excluded) + **explicit 3-step reasoning** (this alert's evidence → competing-signal analysis → conclusion)
- **Change (measurable)**: output adds `competing_signals_observed` / `evidence_alignment` fields; `run_baseline.sh` supports composite injection and dual-alert waiting
- **Result**: **9/9 hit** (single 3/3 + composite 3/3); **grounding 100%** (metric 8/8, log 8/8)
  - Key reversal: HighLatency in error+latency **0.8 wrong → 0.9 correct** (`evidence_alignment=consistent`, `competing_signals=[]` — once retrieval was fixed, the model never even saw the competing signal; root fix beats safety net)
  - vs v7b on single faults: error 0.8→0.9, memory 0.7→0.8, latency 0.9 held (no regression)
- **New finding**: #94 (HighMemoryUsage in error+memory) was correct but confidence **0.4** (metric evidence only, no ERROR logs → correctly weak-calibrated under the rules). Same scenario was 0.7 in the previous round — correct low-confidence is calibration working, but stability needs observation
- **Lesson**: **discrimination = retrieval isolation (upstream: don't feed dirty evidence) + metric-signature verification (downstream: teach the model to recognize it)**; when the upstream is fixed, the downstream safety net rarely needs to fire

### v9 — Executable evidence citations (citation index + backend resolution)
- **Context**: after v8, the last "engineering trust" gap was that **citations were text, not actions** (`[metric: ...]` / `[log: "..."]`) — which query produced them? How do you click through and verify? And if the LLM were allowed to generate queries/links itself, it could hallucinate them.
- **Change (root fix)**: **the LLM cites *evidence indexes*, never generates *evidence content*** —
  - Tool layer: `LokiToolService` / `PrometheusToolService` record a `QueryRecord` after every successful real query (PromQL/LogQL + hit count + window) — the backend is the **single source of truth** for queries
  - Orchestration: `AlertAnalysisService` builds an **EVIDENCE INDEX** (`[E1]`..`[En]` + real query + summary) into the user prompt
  - Prompt layer: system rule 10 — cite only `[E{n]}` numbers; **never invent indexes, queries, or URLs**
  - Resolution layer: the backend extracts `[E{n}]` from the LLM output and maps each to the real query + a **Grafana explore deep link**, persisted as `evidenceCitations`
- **Anti-fabrication design**: a cited index that does not exist in the registry → `resolved=false` with **no query/url** — the model can never inject fake evidence (trust is guaranteed by the architecture, not by model compliance)
- **Result**: full regression **9/9 kept from v8** (error 0.9×4 / latency 0.9×3 / memory 0.8×2, no regression); grounding 100%; integration check (incident #96): LLM cited `[E3]` → backend resolved `rate(http_server_requests_seconds_count{job='demo-app',status=~'5..'}[5m])` + Grafana explore link
- **Tests**: +2 (citation resolution → resolved=true with backend query/url; fabricated index → resolved=false with nothing), 7/7 total
- **Lesson**: *"trust the index, not the model's memory"* — evidence trustworthiness should be **guaranteed by architecture** (index→real-query mapping lives in the backend), not by **prompt compliance** (asking the model to be honest). Citing is *selection*; generating content is *creation* — let the model do the former and the backend do the latter

### v10 — Scale the eval set: 12 scenarios, 19 scored alerts
- **Context**: 9/9 on 3 faults looked saturated, but it only covered same-category single/composite faults. The set was expanded to **12 scenarios** adding: a **downstream** fault (dependency timeout → 502, a *different* root cause that still raises HighErrorRate), **staggered** faults (stop A, fire B while A's logs linger — a timing-isolation test), and a **healthy control** (a synthetic alert with nothing wrong).
- **Result**: root-cause accuracy held on the original cases but the new scenarios immediately exposed calibration gaps (memory confidence, healthy verdict, stale-log attribution).
- **Lesson**: **a passing 9/9 means the eval set is too small, not that the system is done**. New scenario *classes* (new fault mode, new timing, explicit negative case) are what reveal new failure modes.

### v11 — Time-overlapping (staggered) discrimination
- **Change**: strengthened discrimination for alerts whose correlation window still contains the *previous* fault's logs; tightened orchestration so each alert is scored against its own metric signature.
- **Result**: a latency alert fired on top of residual error logs, and a fresh error fired on top of residual downstream logs, were both attributed correctly.
- **Lesson**: the hardest competing signal is a **real but past** incident's evidence — retrieval isolation plus signature verification matter more than asking the model to "be careful".

### v12 — Category-scoped retrieval + healthy calibration
- **Change**: every tool call is scoped to the alert's own category (retrieval keywords, log filters, runbook match), and the prompt explicitly defines a healthy verdict as a low-confidence, non-incident outcome.
- **Result**: **19/19 root causes correct**, including the healthy control correctly answering "no anomaly".
- **Lesson**: **scope the evidence you feed the model per alert category**, and remember that a confidence score is about the *incident*, not the model's self-assurance.

### v13 — Verbatim grounding, confidence semantics, and a deterministic guardrail
This round started as "polish two blemishes" and the 100%-grounding audit forced four deeper, evidence-backed fixes (each isolated, re-measured in its own run — see `baseline_results_v13_round1..4.csv`):

- **v13a — Log citation format (a shell, not a hallucination).** Error cases emitted zero checker-recognizable citations because the prompt never taught the exact `[log: "<verbatim>"]` double-quoted form; the model paraphrased in single quotes. The quotes were real but unresolvable. → taught the two machine-checkable forms and required verbatim copying.
- **v13b — Memory confidence calibration rule was wrong.** The old rule demanded a fault-class log (an OutOfMemory line) that a leak does **not** emit before OOM. → confidence is scored against the alert's **primary signal**; a precisely-quoted heap value above threshold is self-sufficient.
- **v13c — The grounding checker caught a bug in *my own monitoring query*.** Error-rate grounding hit 95%: the model summed two 5xx-rps series (0.077 + 0.091) and quoted a total the query never returned. Root cause: the tool's PromQL was `rate(5xx)` — **requests-per-second split across series**, not a proportion (it only *looked* like 70% on a single fault). → replaced with the exact alert expression `sum(rate(5xx)) / sum(rate(all))`, a single scalar, and forbade cross-series arithmetic in the prompt.
- **v13d — Mandatory citation recall.** In a latency+memory composite the latency injection log *was retrieved* but not cited (evidence in hand, missing from the chain) — a **recall**, not precision, gap. → "if any line matches THIS alert's signature you MUST cite one", including inside composites.
- **v13e — Confidence semantics + a deterministic guardrail.** A healthy run briefly scored 0.9 ("I am very sure it is healthy"), and memory verdicts drifted 0.4–0.7 run to run even while quoting heap ≈ 0.987. Two changes: (1) the prompt defines confidence as **probability that a real, actionable incident is happening now**, with a HEALTHY override (normal values → 0.3–0.5) and a rewritten rule distinguishing a *confirmed concurrent fault* (never lowers this alert's score) from an *unexplained conflicting signal* (cap 0.5); (2) a narrow backend **guardrail** `applyHeapConfidenceGuardrail` — if the model itself cites heap above the 0.85 alert threshold but returns < 0.7, the backend raises it to 0.7. It only reads a value the model cited, only ever raises, and is covered by two unit tests. In the final run it fired **3 times**, deterministically removing the residual drift.
- **Final result**: **19/19 root causes** · **metric grounding 19/19** · **log grounding 14/14** (5 metric-only verdicts are by design: 4 pre-OOM memory + healthy) · confidence **healthy 0.5 / faults 0.7–0.9** · **9/9 unit tests**.
- **Lesson**: *"Measure, then trust the architecture over the model's mood."* The prompt is a soft guide and should stay primary; for a property you need to **guarantee** (an internally-contradictory verdict), a small, explainable, tested deterministic guardrail beats rewording the prompt and hoping. Also: a grounding checker is not an LLM-only tool — it will happily fail your own PromQL.

---

## 3. Quantified summary

| Metric | Start | Final (v13) |
|---|---|---|
| Eval coverage | 3 single faults | **12 scenarios / 19 scored alerts** (single · concurrent · staggered · healthy control) |
| Root-cause accuracy | 2/3 (v1) | **19/19** (every alert, including a correct healthy "no incident") |
| Citation grounding | 0% (v2, all fabricated) | **metric 19/19 + log 14/14 (100%)**; 5 metric-only verdicts are by design, not misses |
| Error-rate metric semantics | raw 5xx rps across series (mis-read as a %) | **single scalar `sum(5xx)/sum(all)`**, aligned with the alert rule |
| Latency diagnosis confidence | 0.5 (no evidence) | **0.9** (alert-aware retrieval + composite/staggered discrimination) |
| Memory diagnosis confidence | oscillated 0.4–0.8 (log rule wrong) | **stable 0.7** (heap primary-signal rule + deterministic guardrail) |
| Healthy control | briefly over-confident 0.9 | **0.5** (confidence = incident probability, not self-assurance) |
| Composite / staggered discrimination | first multi-alert round polluted | **correct per-alert attribution** even with a concurrent or residual fault |
| Loki log pipeline | HTTP 400 (broken day one) | **HTTP 200, streams 0→1** |
| Executable citations (v9) | citations were text, unreproducible | **LLM cites index → backend resolves real query + Grafana deep link** (invented index → resolved=false) |
| Unit tests | – | **9/9**, including the guardrail firing / not-firing both ways |

---

## 4. Lessons in practice

### Discovering the bug (the grounding concept)
> *"I built a grounding checker that verifies every citation verbatim against the retrieved context. That's when I found my v2 '100% citation rate' was format compliance — the log tool had been silently 400-failing since day one, so those log quotes were fabricated. Fixing a one-character LogQL bug (single-quote vs double-quote) turned fabricated citations into grounded evidence — latency case confidence recovered from 0.5 to 0.9."*

### The architecture decision (correlation window)
> *"My sliding-window log query (`now - 5min`) mixed stale logs from the previous incident into the current analysis — the citations were grounded but irrelevant. The fix: anchor the window to the alert's receivedAt — a correlation window of `[alert - 2min, alert]`. Root causes happen before the alert fires; querying forward from 'now' is wrong twice."*

### Tool design (alert-aware search)
> *"My log tool only searched ERROR/Exception — but latency incidents leave WARN/DEBUG evidence. I made the retrieval alert-aware: query keywords derive from the alert category, so a latency alert also searches for sleep/timeout signals. Tool signatures should adapt to the task."*

### Methodology (eval-driven)
> *"I treat evaluation like a grading rubric — fixed fault injections with known answers (measure, not train), settle isolation between cases, one variable at a time, and a grounding check that verifies evidence provenance, not just format."*

### Trust by architecture (executable citations)
> *"The last trust gap wasn't diagnosis accuracy — it was that my agent's citations were text, not actions. I made evidence executable: tools record every query they really ran, the prompt exposes an evidence index of real queries, and the model cites by number. The backend resolves each number to the actual query and a Grafana deep link; an invented index resolves to nothing. Citations became auditable by construction, not by model compliance."*

### Prompt guides, the guardrail guarantees (v13)
> *"My grounding checker didn't just catch LLM mistakes — it caught a bug in my own error-rate PromQL, which returned per-second rates instead of a ratio. And I learned to stop arguing with non-determinism: my memory verdict cited heap at 98.7% yet sometimes returned 0.4. I keep the prompt as the primary guide, but for a property I must guarantee I added a 15-line deterministic guardrail that only raises a self-contradictory score, and two unit tests that pin it. In the final run it fired three times. Soft guidance for quality, hard guardrails for guarantees."*

---

## 5. Known limitations & next steps

| Issue | Status | Direction |
|---|---|---|
| Alertmanager may re-deliver a webhook → duplicate Incidents | open | idempotency: dedupe by alert fingerprint |
| `root_cause_hypothesis` is occasionally a summary (citations live in `analysis_detail`) | acceptable | judge evaluation on the full output ("measure what matters — check the full output, not one field") |
| Memory cases without ERROR logs → metric-only verdict | by design (v13) | heap above threshold is self-sufficient (0.7); a deterministic guardrail enforces it, so the confidence no longer drifts run to run |
| RAG is keyword-based (not semantic) | current | pgvector semantic search deferred — with 3 runbooks a vector store is over-engineering; revisit when the KB grows |
