package com.example.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * AI Incident Diagnosis Agent — application entry point.
 * <p>
 * Core workflow:
 * 1. Alertmanager delivers alerts to the agent via webhook
 * 2. The agent extracts alert context (severity, service, alertname, description)
 * 3. Tools collect context:
 *    - queryMetrics()   → reads relevant metrics from Prometheus
 *    - searchLogs()     → searches related logs in Loki
 *    - searchRunbooks() → RAG retrieval over the runbook knowledge base
 * 4. The LLM produces a structured root-cause analysis from the context
 * 5. The incident is persisted and exposed through the REST API / dashboards
 * 6. (Future) auto-create tickets / send notifications / suggest remediation
 * <p>
 * AI capability levels addressed (per the "Six Levels of AI Products" framework):
 * - L2 Grounded AI (RAG) → runbook retrieval
 * - L3 Tool-using AI     → Prometheus / Loki tool calls
 * - L4 LLM Workflow      → fixed alert-analysis pipeline
 * - (Target) L5 Agentic Core → multi-round investigation loop
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
