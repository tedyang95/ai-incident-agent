package com.example.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Prometheus 工具服务
 * 作为 AI Agent 的 tool（工具调用），让 LLM 能查询实时指标。
 *
 * 对应 AI 产品六层框架中的 L3: Tool-using AI
 * 面试中可以说："I implemented Prometheus query as an LLM tool function,
 * allowing the agent to retrieve real-time metrics during root cause analysis."
 */
@Service
public class PrometheusToolService {

    private static final Logger log = LoggerFactory.getLogger(PrometheusToolService.class);

    @Value("${app.prometheus.url:http://localhost:9090}")
    private String prometheusUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 执行 PromQL 查询，返回指标当前值
     * 这是暴露给 LLM 的核心 tool function
     *
     * @param query PromQL 查询表达式，例如 "up{job='demo-app'}"
     * @return 查询结果的文本摘要
     */
    public String query(String query) {
        try {
            // 使用 URI 模板变量：让 RestTemplate 负责正确的 URL 编码
            // （避免手写 encode 造成 % 二次编码，导致 PromQL 解析失败）
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
            return sb.toString();

        } catch (Exception e) {
            log.error("Prometheus query failed: {}", e.getMessage());
            return "Prometheus query error: " + e.getMessage();
        }
    }

    /**
     * 查询指定服务的关键指标概览
     * 用于告警分析时自动收集上下文
     */
    public String getServiceOverview(String service) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Service Overview: ").append(service).append(" ===\n\n");

        // 1. 服务是否在线
        sb.append("[Up/Down]\n").append(query("up{job='" + service + "'}")).append("\n");

        // 2. 请求速率
        sb.append("[Request Rate]\n").append(query("rate(http_server_requests_seconds_count{job='" + service + "'}[5m])")).append("\n");

        // 3. 错误率
        sb.append("[Error Rate]\n").append(query("rate(http_server_requests_seconds_count{job='" + service + "',status=~'5..'}[5m])")).append("\n");

        // 4. p99 延迟
        sb.append("[p99 Latency]\n").append(query("histogram_quantile(0.99, rate(http_server_requests_seconds_bucket{job='" + service + "'}[5m]))")).append("\n");

        // 5. JVM 堆内存使用率
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
