# 🚨 AI Incident Triage Agent

> **When your server breaks, this AI tells you what broke, why, and how to fix it — in minutes.**

An LLM-powered agent that turns production alerts into root-cause analysis. It ingests **Prometheus alerts**, pulls **metrics + logs** through tool calling, retrieves **runbooks via RAG**, and returns a structured diagnosis (root cause, confidence score, suggested actions) — saved to PostgreSQL and exposed via REST API.

Built to answer the question every on-call engineer hates: *"What's actually wrong, right now?"*

---

## ✨ Why this project stands out

| Signal | Data |
|---|---|
| 🎯 **Fault diagnosis** | **3/3** scenarios correctly diagnosed (error / latency / memory) |
| 🔍 **Evidence grounding** | **100%** of `[metric:]` / `[log:]` citations verified verbatim against retrieved context |
| 📈 **Tuning impact** | latency diagnosis confidence **0.5 → 0.9** after fixing a one-character LogQL bug + alert-aware retrieval |
| 🏭 **Real stack** | Prometheus → Alertmanager → AI Agent → Loki → Grafana → PostgreSQL, all in Docker Compose |

---

## 🏗️ How it works

```
[fault injected in demo-app]  (the monitored microservice)
   │  metrics + logs
   ▼
[Prometheus] ──alert──▶ [Alertmanager] ──webhook──▶ [AI Agent · Spring Boot :8081]
   │                                                   │
   │  tool calling: query metrics                      │  tool calling: search logs
   ▼                                                   ▼
[Grafana dashboards]  ◀── incidents ── [PostgreSQL]   [Loki + Promtail]
                                                        │
                                              [Runbook KB (RAG)]
```

**Agent pipeline (LLM workflow):**
1. **Collect context** — Prometheus metrics (tool call) + Loki logs (tool call, correlation window anchored to alert time) + Runbook RAG retrieval
2. **Build prompt** — structured context + strict citation rules
3. **Call LLM** — GPT-4o-mini returns structured JSON: root cause, confidence, evidence citations, suggested actions
4. **Persist** — incident + full context snapshot saved to PostgreSQL (auditable, reproducible)
5. **Expose** — REST API for querying incidents and stats

**AI capability levels addressed** (per the "Six Levels of AI Products" framework):
- **L2 Grounded AI / RAG** — runbook retrieval
- **L3 Tool-using AI** — Prometheus + Loki as LLM tools
- **L4 LLM Workflow** — fixed, reliable alert-analysis pipeline with structured output
- **L5 Agentic Core** — multi-round investigation loop (roadmap)

---

## 🔬 The tuning story (v1 → v7b)

| Run | Change | Result | Lesson |
|---|---|---|---|
| v1 | first baseline | 2/3 hit, cross-case pollution | evaluation cases leak into each other |
| v2 | settle isolation + citation prompt | 3/3 hit | **format compliance ≠ grounded** (log tool was silently broken) |
| v5 | **fixed LogQL single-quote bug** | 3/3, citations became 100% real | one character can break your whole evidence chain |
| v6 | **correlation-window retrieval** | error 0.9, cross-contamination gone | query the alert's time window, not "now" |
| v7b | **alert-aware keyword search** | latency 0.5 → 0.9 | tool signatures should adapt to the task |

Full experiment log with data: **[docs/TUNING.md](docs/TUNING.md)**

---

## 🧪 How it's tested

5 unit tests cover **every branch** of the core service — LLM failure (graceful degradation), success (structured output parsing), invalid JSON (fallback), markdown-wrapped JSON (parser tolerance), oversized context (truncation guard). All external dependencies mocked: deterministic, isolated, ~1.7s.

→ **[docs/TESTING-STRATEGY.md](docs/TESTING-STRATEGY.md)**

---

## 🚀 Quick start

### Prerequisites
- Docker & Docker Compose
- OpenAI API Key ([platform.openai.com/api-keys](https://platform.openai.com/api-keys))

### 1. Configure API key
```bash
cp .env.example .env
# Edit .env and set OPENAI_API_KEY=your-actual-key
```

### 2. Start everything (8 services)
```bash
docker compose up -d
```

### 3. Verify
```bash
docker compose ps
```

### 4. Dashboards
| Service | URL | Default credentials |
|---|---|---|
| Grafana | http://localhost:3000 | admin / admin |
| Prometheus | http://localhost:9090 | – |
| Alertmanager | http://localhost:9093 | – |
| Demo App | http://localhost:8080 | – |
| Agent API | http://localhost:8081 | – |

### 5. Trigger a test alert
```bash
# Inject an error fault into the demo app
curl -X POST http://localhost:8080/api/admin/fail/error

# ~1 min later, Prometheus fires → Alertmanager → Agent analyzes automatically

# Or send a manual test alert:
curl -X POST "http://localhost:8081/api/alert/test?alertname=HighErrorRate&severity=critical&service=demo-app"
```

### 6. View results
```bash
curl http://localhost:8081/api/incidents
curl http://localhost:8081/api/incidents/stats
curl http://localhost:8081/api/incidents/1
```

### 7. Stop fault injection
```bash
curl -X POST http://localhost:8080/api/admin/fail/stop
```

---

## 🔌 Fault injection endpoints (demo app)

| Endpoint | Effect | Alert triggered |
|---|---|---|
| `POST /api/admin/fail/error` | All POST /api/orders return 500 | HighErrorRate |
| `POST /api/admin/fail/latency` | All requests delayed 2–5s | HighLatency |
| `POST /api/admin/fail/memory` | Memory leak (~10MB/s) | HighMemoryUsage |
| `POST /api/admin/fail/stop` | Stop all faults | – |
| `GET /api/admin/fail/status` | View active faults | – |

## 🔌 Agent API

- `POST /api/alert/webhook` — receive alerts from Alertmanager
- `POST /api/alert/test` — trigger a manual test alert
- `GET /api/incidents` — list incidents (paginated, filterable)
- `GET /api/incidents/{id}` — incident detail
- `GET /api/incidents/stats` — statistics

---

## 🧰 Tech stack

| Layer | Technology |
|---|---|
| AI Agent | Java 17 · Spring Boot 3.3 · Spring AI 1.0 |
| LLM | OpenAI GPT-4o-mini (configurable) |
| Metrics | Prometheus + Micrometer |
| Logs | Loki + Promtail |
| Visualization | Grafana |
| Alerting | Alertmanager |
| Database | PostgreSQL 16 (JPA) |
| Demo Service | Spring Boot + H2 + Actuator (with fault injection) |
| Containerization | Docker + Docker Compose |

---

## 📁 Docs

| Doc | What it's for |
|---|---|
| [docs/TUNING.md](docs/TUNING.md) | Full tuning log: methods, data, root-cause findings, lessons learned |
| [docs/TESTING-STRATEGY.md](docs/TESTING-STRATEGY.md) | Why AI systems need special testing: test matrix, mocking strategy, edge cases |

---

## 🗂️ Project structure

```
ai-incident-agent/
├── docker-compose.yml          # One command to start everything
├── demo-app/                   # Monitored service (metrics + logs + fault injection)
├── agent-app/                  # AI Agent core
│   ├── controller/             #   alert webhook + incident query APIs
│   ├── service/                #   AlertAnalysisService — the orchestration brain
│   ├── tool/                   #   PrometheusToolService, LokiToolService (LLM tools)
│   ├── rag/                    #   RunbookRetrievalService (RAG)
│   ├── model/                  #   Incident entity (JPA + structured output)
│   └── repository/             #   IncidentRepository
├── prometheus/                 # scrape config + 5 alert rules
├── alertmanager/               # routes alerts to agent webhook
├── loki/                       # log aggregation (Loki) + collection (Promtail)
├── grafana/                    # auto-provisioned dashboards
├── runbooks/                   # RAG knowledge base
└── docs/                       # TUNING.md, TESTING-STRATEGY.md, eval scripts + results
```

---

## 🗺️ Roadmap

- [ ] **L5 Agentic Core** — multi-round investigation loop (agent decides what to query next)
- [ ] **pgvector semantic search** — upgrade RAG from keyword to vector embedding
- [ ] **Multi-agent orchestration** — separate agents for triage / investigation / notification
- [ ] **Slack / Teams integration** — push results to chat
- [ ] **Jira / Linear integration** — auto-create incident tickets
- [ ] **Auto-remediation** — suggest and (with approval) execute fixes
- [ ] **Human-in-the-loop UI** — approve / reject analysis with feedback loop
- [ ] **Model routing** — small model for triage, large model for deep analysis

## License

MIT
