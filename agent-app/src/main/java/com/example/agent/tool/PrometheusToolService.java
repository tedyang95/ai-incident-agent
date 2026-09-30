package com.example.agent.tool;

import com.example.agent.evidence.QueryRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Prometheus query tool service.
 * <p>
 * Exposed to the LLM as a tool so the agent can retrieve real-time metrics
 * during root-cause analysis (AI capability level L3: Tool-using AI).
 */
@Service
public class PrometheusToolService {

    private static final Logger log = LoggerFactory.getLogger(PrometheusToolService.class);

    @Value("${app.prometheus.url:http://localhost:9090}")
    private String prometheusUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * All queries that succeeded during this analysis run (v9 executable evidence).
     * getServiceOverview() executes several PromQL queries and records all of them;
     * a volatile reference swap keeps it thread-safe.
     * AlertAnalysisService reads this after tool invocation to build the EVIDENCE INDEX.
     */
    private volatile List<QueryRecord> lastQueryRecords = new ArrayList<>();

    public List<QueryRecord> getLastQueryRecords() {
        return lastQueryRecords;
    }

    /**
     * Executes a PromQL query and returns the current values.
     * This is the core tool function exposed to the LLM.
     *
     * @param query the PromQL expression, e.g. {@code up{job='demo-app'}}
     * @return a text summary of the query results
     */
    public String query(String query) {
        try {
            // URI template variables: let RestTemplate handle URL encoding.
            // (Avoids double-encoding of '%' by hand-written encode, which would
            // break the PromQL expression.)
            String url = prometheusUrl + "/api/v1/query?query={query}";
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class, query);

            if (response == null || !"success".equals(response.get("status"))) {
                return "Prometheus query failed: " + response;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");

            if (results.isEmpty()) {
                return "No data returned for query: " + query;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Prometheus query: ").append(query).append("\n");
            sb.append("Results (").append(results.size()).append("):\n");

            for (Map<String, Object> result : results) {
                @SuppressWarnings("unchecked")
                Map<String, String> metric = (Map<String, String>) result.get("metric");
                @SuppressWarnings("unchecked")
                List<Object> value = (List<Object>) result.get("value");

                sb.append("  metric: ").append(metric).append("\n");
                if (value != null && value.size() >= 2) {
                    sb.append("  value: ").append(value.get(1)).append("\n");
                }
                sb.append("\n");
            }

            log.debug("Prometheus query '{}' returned {} results", query, results.size());
            List<QueryRecord> records = new ArrayList<>(lastQueryRecords);
            records.add(new QueryRecord("PROMETHEUS", query, results.size() + " series", null, null));
            lastQueryRecords = records;
            return sb.toString();

        } catch (Exception e) {
            log.error("Prometheus query failed: {}", e.getMessage());
            return "Prometheus query error: " + e.getMessage();
        }
    }

    /**
     * Fetches a key metrics overview for a service, used to build the analysis context.
     */
    public String getServiceOverview(String service) {
        // Reset the query records at the start of every overview, so the
        // EVIDENCE INDEX only contains queries from this analysis.
        lastQueryRecords = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        sb.append("=== Service Overview: ").append(service).append(" ===\n\n");

        // 1. Is the service up?
        sb.append("[Up/Down]\n").append(query("up{job='" + service + "'}")).append("\n");

        // 2. Request rate
        sb.append("[Request Rate]\n").append(query("rate(http_server_requests_seconds_count{job='" + service + "'}[5m])")).append("\n");

        // 3. Error rate
        sb.append("[Error Rate]\n").append(query("rate(http_server_requests_seconds_count{job='" + service + "',status=~'5..'}[5m])")).append("\n");

        // 4. p99 latency
        sb.append("[p99 Latency]\n").append(query("histogram_quantile(0.99, rate(http_server_requests_seconds_bucket{job='" + service + "'}[5m]))")).append("\n");

        // 5. JVM heap usage
        sb.append("[JVM Heap Usage]\n").append(query("jvm_memory_used_bytes{job='" + service + "',area='heap'} / jvm_memory_max_bytes{job='" + service + "',area='heap'}")).append("\n");

        return sb.toString();
    }

    private String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }
}
