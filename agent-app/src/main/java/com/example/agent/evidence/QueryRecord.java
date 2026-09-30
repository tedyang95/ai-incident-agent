package com.example.agent.evidence;

import java.time.Instant;

/**
 * A record of a query actually executed by a tool — the raw source of
 * executable evidence (citations).
 * <p>
 * Tools (PrometheusToolService / LokiToolService) record one of these after
 * every successful real query; AlertAnalysisService uses them to build the
 * EVIDENCE INDEX and to resolve citations to clickable artifacts.
 * <p>
 * Key design (v9, executable evidence chain): the query string (PromQL/LogQL)
 * is always generated and recorded by the backend tools — the LLM never
 * generates queries. The model may only reference evidence by index, so it
 * cannot fabricate a query or URL.
 */
public record QueryRecord(
        String source,   // PROMETHEUS / LOKI
        String query,    // the real query that succeeded (PromQL / LogQL)
        String summary,  // one-line result summary (e.g. "3 matching log lines" / "1 series")
        Instant from,    // window start (null = instant query)
        Instant to       // window end (null = instant query)
) {
    public String window() {
        if (from == null || to == null) return "instant";
        return from + " ~ " + to;
    }
}
