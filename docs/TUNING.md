# AI Incident Triage Agent — Tuning Log (v1 → v9)

> How this AI system was iteratively tuned toward accurate diagnosis: what changed each round, what was observed, and why it was changed.
> Companion tooling: `docs/eval/run_baseline.sh` (evaluation), `docs/eval/check_grounding.py` (citation-veracity checker)
> Data: `docs/eval/baseline_results_{v1..v9}.csv`, plus the PostgreSQL `incidents` table (every raw analysis is queryable by id)
> 中文版: [docs/TUNING-zh.md](docs/TUNING-zh.md)

---

## 0. Methodology: why AI systems need tuning

**Evaluation-driven development** — we do not train the model; we measure the *system's reasoning behavior*.

| Concept | How this project applies it |
|---|---|
| **Ground truth** | Inject known faults (error / latency / memory) → the correct answer is *known* → auto-scoring. **Measure, not train.** |
| **Eval set** | `docs/eval/eval-cases.json` (6 cases: 3 single + 3 composite, with per-alertname ground truth) |
| **Eval script** | `run_baseline.sh`: inject fault → load traffic → wait for AI analysis → pull Postgres → write CSV |
| **Isolation (settle)** | Wait for alerts to clear + 30s before each case, so one case's "dirty data" never pollutes the next |
| **Grounding check** | `check_grounding.py`: verifies verbatim that every `[metric:]` / `[log:]` citation exists in the retrieved context |

**Core position**: *"Evaluation should be like a grading rubric — fixed fault injections with known answers; verify not just whether the diagnosis is right, but whether the cited evidence actually exists."*

---

## 1. The three fault scenarios (ground truth)

| Fault | Injection | Expected alert | Expected root-cause keywords |
|---|---|---|---|
| error | POST /api/orders returns 500 | HighErrorRate | RuntimeException, error rate |
| latency | all requests delayed 2-5s | HighLatency | p99 latency, delay |
| memory | memory leak ~10MB/s | HighMemoryUsage | heap memory, jvm |

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
- **Change (safety net)**: system prompt rules 8/9 — **discrimination instructions** (evidence must match THIS alert's metric signature: error-rate↔error rate / latency↔p99 / memory↔heap; competing signals go to `competing_signals_observed`, never the primary root cause; confidence capped at 0.5 when they cannot be excluded) + **explicit 3-step reasoning** (this alert's evidence → competing-signal triage → conclusion)
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

---

## 3. Quantified summary

| Metric | Start | Final |
|---|---|---|
| Fault-diagnosis hit rate | 2/3 (v1) | **9/9 (v8→v9 held: single 3/3 + composite 3/3)** |
| Citation grounding | 0% (v2, all fabricated) | **100% (v9 regression: metric 7/7 + log 7/7 verbatim)** |
| Latency diagnosis confidence | 0.5 (no evidence) | **0.9** (alert-aware retrieval + composite discrimination) |
| Error diagnosis confidence | 0.7 | **0.9** (after correlation window) |
| Memory diagnosis confidence | 0.7 | **0.8** (single; correctly lowers to 0.4 without log evidence) |
| Composite discrimination | first round 2/3 (HighLatency polluted) | **3/3** (reversed after retrieval isolation) |
| Loki log pipeline | HTTP 400 (broken day one) | **HTTP 200, streams 0→1** |
| Executable citations (v9) | citations were text, unreproducible | **LLM cites index → backend resolves real query + Grafana deep link** (invented index → resolved=false) |

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

---

## 5. Known limitations & next steps

| Issue | Status | Direction |
|---|---|---|
| Alertmanager may re-deliver a webhook → duplicate Incidents | open | idempotency: dedupe by alert fingerprint |
| `root_cause_hypothesis` is occasionally a summary (citations live in `analysis_detail`) | acceptable | judge evaluation on the full output ("measure what matters — check the full output, not one field") |
| Memory cases without ERROR logs → confidence 0.7 (metric only) | by design | correctly lowering confidence without log evidence is calibration, not a defect |
| RAG is keyword-based (not semantic) | current | pgvector semantic search (see README Roadmap) |
