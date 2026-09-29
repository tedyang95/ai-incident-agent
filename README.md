# AI Incident Triage Agent

> An AI-powered alert triage and root cause analysis agent for microservices.
> Receives alerts from Prometheus Alertmanager, queries metrics and logs via tool calling,
> retrieves runbooks via RAG, and generates structured root cause analysis with confidence scores.

## What It Does

```
Alert (Prometheus)
  → Alertmanager routes to Agent webhook
  → Agent collects context:
      ├─ Prometheus metrics (tool calling)
      ├─ Loki logs (tool calling)
      └─ Runbook RAG retrieval
  → LLM generates structured root cause analysis (JSON)
  → Saved to PostgreSQL + exposed via REST API
  → (Future) auto-create tickets, notifications, remediation suggestions
```

## Architecture

```
┌─────────────┐     ┌──────────────┐     ┌──────────────────┐
│  Prometheus  │────▶│ Alertmanager │────▶│   AI Agent App   │
│  (metrics)   │     │  (routing)   │     │  (Spring Boot +  │
└─────────────┘     └──────────────┘     │   Spring AI)     │
                                            └────────┬─────────┘
┌─────────────┐                               │
│    Loki     │◀──────────────────────────────┘
│  (logs)     │   tool calling
└─────────────┘

┌─────────────┐     ┌──────────────┐
│  Grafana    │     │  PostgreSQL  │
│ (dashboard) │     │  (incidents) │
└─────────────┘     └──────────────┘

┌─────────────┐
│  Demo App   │  ← monitored service (generates metrics + logs)
│ (Spring Boot)│  ← includes fault injection endpoints for testing
└─────────────┘
```

## Tech Stack

| Component | Technology |
|---|---|
| AI Agent | Java 17, Spring Boot 3.3, Spring AI 1.0 |
| LLM | OpenAI GPT-4o-mini (configurable) |
| Database | PostgreSQL 16 |
| Metrics | Prometheus + Micrometer |
| Logs | Loki + Promtail |
| Visualization | Grafana |
| Alerting | Alertmanager |
| Demo Service | Spring Boot + H2 + Actuator |
| Containerization | Docker + Docker Compose |

## AI Product Level (per "Six Levels of AI Products" framework)

This project operates at multiple levels:
- **L2 (Grounded AI / RAG)**: Runbook retrieval based on alert context
- **L3 (Tool-using AI)**: Prometheus metric query + Loki log search as LLM tool functions
- **L4 (LLM Workflow)**: Fixed, reliable alert analysis pipeline with structured output
- **Target L5 (Agentic Core)**: Multi-round investigation loop (future enhancement)

## Quick Start

### Prerequisites
- Docker & Docker Compose
- OpenAI API Key (get one at https://platform.openai.com/api-keys)

### 1. Configure API Key
```bash
cp .env.example .env
# Edit .env and set OPENAI_API_KEY=your-actual-key
```

### 2. Start Everything
```bash
docker-compose up -d
```

This starts 8 services: demo-app, prometheus, alertmanager, loki, promtail, grafana, postgres, agent-app.

### 3. Verify Services Are Running
```bash
docker-compose ps
```

### 4. Access Dashboards
| Service | URL | Default Credentials |
|---|---|---|
| Grafana | http://localhost:3000 | admin / admin |
| Prometheus | http://localhost:9090 | - |
| Alertmanager | http://localhost:9093 | - |
| Demo App | http://localhost:8080 | - |
| Agent API | http://localhost:8081 | - |

### 5. Trigger a Test Alert
```bash
# Inject an error fault in the demo app
curl -X POST http://localhost:8080/api/admin/fail/error

# Wait ~1 minute for Prometheus to detect high error rate
# Alertmanager will send the alert to the Agent automatically

# Or trigger a manual test alert directly:
curl -X POST "http://localhost:8081/api/alert/test?alertname=HighErrorRate&severity=critical&service=demo-app"
```

### 6. View Analysis Results
```bash
# List all incidents
curl http://localhost:8081/api/incidents

# View stats
curl http://localhost:8081/api/incidents/stats

# View specific incident
curl http://localhost:8081/api/incidents/1
```

### 7. Stop Fault Injection
```bash
curl -X POST http://localhost:8080/api/admin/fail/stop
```

## Fault Injection Endpoints (Demo App)

The demo app includes controlled fault injection for testing the agent:

| Endpoint | Effect | Alert Triggered |
|---|---|---|
| `POST /api/admin/fail/error` | All POST /api/orders return 500 | HighErrorRate |
| `POST /api/admin/fail/latency` | All requests delayed 2-5 seconds | HighLatency |
| `POST /api/admin/fail/memory` | Memory leak (10MB/sec) | HighMemoryUsage |
| `POST /api/admin/fail/stop` | Stop all faults | - |
| `GET /api/admin/fail/status` | View active faults | - |

## API Endpoints (Agent App)

### Alert Webhook
- `POST /api/alert/webhook` — Receive alerts from Alertmanager
- `POST /api/alert/test` — Trigger a test alert manually

### Incidents
- `GET /api/incidents` — List incidents (paginated, filterable by severity/service)
- `GET /api/incidents/{id}` — Get incident detail
- `GET /api/incidents/stats` — Get statistics

## Project Structure

```
ai-incident-agent/
├── docker-compose.yml          # One command to start everything
├── .env.example                # API key configuration template
├── README.md
├── demo-app/                   # Monitored service (generates metrics + logs)
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/com/example/demo/
│       ├── DemoApplication.java
│       ├── controller/EcommerceController.java    # Business API + fault injection
│       ├── config/FaultState.java                 # Fault state management
│       └── model/{Product,Order}.java
├── agent-app/                  # AI Agent core service
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/com/example/agent/
│       ├── AgentApplication.java
│       ├── controller/
│       │   ├── AlertWebhookController.java        # Receive alerts from Alertmanager
│       │   └── IncidentController.java            # Query incidents API
│       ├── service/
│       │   └── AlertAnalysisService.java          # Core orchestration (the brain)
│       ├── tool/
│       │   ├── PrometheusToolService.java         # Metric query (LLM tool)
│       │   └── LokiToolService.java               # Log search (LLM tool)
│       ├── rag/
│       │   └── RunbookRetrievalService.java       # RAG: runbook retrieval
│       ├── model/
│       │   └── Incident.java                      # JPA entity + structured output
│       └── repository/
│           └── IncidentRepository.java
├── prometheus/
│   ├── prometheus.yml            # Scrape config
│   └── alert_rules.yml           # 5 alert rules (down, error, latency, memory, pool)
├── alertmanager/
│   └── alertmanager.yml          # Route alerts to agent webhook
├── loki/
│   ├── loki-config.yml           # Log aggregation
│   └── promtail-config.yml       # Log collection from Docker
├── grafana/
│   └── provisioning/datasources/
│       └── datasources.yml       # Auto-configure Prometheus + Loki
└── runbooks/                      # RAG knowledge base
    ├── high-error-rate.md
    ├── high-latency.md
    └── service-down.md
```

## Evaluation (Week 3 focus)

To measure agent accuracy, create a labeled test set:
1. Inject each fault type (error, latency, memory, down)
2. Record the alert and agent's analysis
3. Label the correct root cause (ground truth)
4. Measure: root cause identification accuracy, confidence calibration, false positive rate

Target: > 75% accuracy on 30+ labeled scenarios.

## Future Enhancements (Roadmap)

- [ ] **L5 Agentic Core**: Multi-round investigation loop (agent decides what to query next)
- [ ] **pgvector semantic search**: Upgrade RAG from keyword to vector embedding
- [ ] **Multi-agent orchestration**: Separate agents for triage, investigation, and notification
- [ ] **Slack/Teams integration**: Push analysis results to chat
- [ ] **Jira/Linear integration**: Auto-create incident tickets
- [ ] **Auto-remediation**: Agent suggests and (with approval) executes fix actions
- [ ] **Frontend dashboard**: React/Next.js dashboard for incident review
- [ ] **Human-in-the-loop UI**: Approve/reject agent analysis with feedback loop
- [ ] **Model routing**: Use small model for triage, large model for deep analysis

## License

MIT
