package com.example.agent.tool;

import com.example.agent.evidence.QueryRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Loki 日志搜索工具服务
 * 作为 AI Agent 的 tool（工具调用），让 LLM 能搜索实时日志。
 *
 * 对应 AI 产品六层框架中的 L3: Tool-using AI
 * 面试中可以说："I integrated Loki log search as a tool function,
 * enabling the agent to correlate metrics with logs during incident triage."
 */
@Service
public class LokiToolService {

    private static final Logger log = LoggerFactory.getLogger(LokiToolService.class);

    @Value("${app.loki.url:http://localhost:3100}")
    private String lokiUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 最近一次成功执行的查询记录（v9 证据链可执行化）。
     * AlertAnalysisService 调用工具后读取，用于构建 EVIDENCE INDEX。
     * 注意：分析流程为同步单线程，实例字段足够；如改多线程需改为 ThreadLocal。
     */
    private volatile QueryRecord lastQueryRecord;

    public QueryRecord getLastQueryRecord() {
        return lastQueryRecord;
    }

    /**
     * 搜索日志，返回匹配的日志行
     * 这是暴露给 LLM 的核心 tool function
     *
     * @param service  服务名，用于过滤
     * @param keyword  搜索关键词
     * @param minutes  搜索最近多少分钟的日志
     * @param limit    返回最大条数
     * @return 日志文本摘要
     */
    public String searchLogs(String service, String keyword, int minutes, int limit) {
        try {
            // Loki LogQL 查询: {service="demo-app"} |= "keyword"
            String logql = "{service=\"" + service + "\"} |= `" + keyword + "`";
            String end = String.valueOf(Instant.now().getEpochSecond()) + "000000000";
            String start = String.valueOf(Instant.now().minus(minutes, ChronoUnit.MINUTES).getEpochSecond()) + "000000000";

            // 使用 URI 模板变量：让 RestTemplate 负责正确的 URL 编码
            String url = lokiUrl + "/loki/api/v1/query_range"
                    + "?query={query}"
                    + "&start={start}"
                    + "&end={end}"
                    + "&limit={limit}";

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class, logql, start, end, limit);

            if (response == null || !"success".equals(response.get("status"))) {
                return "Loki query failed: " + response;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> streams = (List<Map<String, Object>>) data.get("result");

            if (streams == null || streams.isEmpty()) {
                return "No logs found for service='" + service + "', keyword='" + keyword +
                        "' in last " + minutes + " minutes.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== Logs for service=").append(service)
                    .append(", keyword=").append(keyword)
                    .append(" (last ").append(minutes).append("min) ===\n\n");

            int count = 0;
            for (Map<String, Object> stream : streams) {
                @SuppressWarnings("unchecked")
                List<List<String>> values = (List<List<String>>) stream.get("values");
                if (values != null) {
                    for (List<String> entry : values) {
                        if (count >= limit) break;
                        String timestamp = entry.get(0);
                        String line = entry.get(1);
                        sb.append(formatTimestamp(timestamp)).append(" ").append(line).append("\n");
                        count++;
                    }
                }
            }

            sb.append("\n(Total: ").append(count).append(" log lines)\n");
            log.debug("Loki search for '{}' in '{}' found {} lines", keyword, service, count);
            this.lastQueryRecord = new QueryRecord(
                    "LOKI", logql, count + " matching log lines",
                    Instant.now().minus(minutes, ChronoUnit.MINUTES), Instant.now());
            return sb.toString();

        } catch (Exception e) {
            log.error("Loki search failed: {}", e.getMessage());
            return "Loki search error: " + e.getMessage();
        }
    }

    /**
     * 按锚定时间窗搜索日志（correlation window）
     * 与告警触发时刻对齐：只查告警前 N 分钟到告警时刻的日志（根因发生在告警前），
     * 避免"当前时间往前 N 分钟"的滑动窗口把相邻告警/上一故障的残留日志带进来。
     *
     * @param service        服务名，用于过滤
     * @param keyword        搜索关键词
     * @param from           窗口起点（告警触发时刻 - N 分钟）
     * @param to             窗口终点（告警触发时刻）
     * @param limit          返回最大条数
     * @param excludeKeyword 排除关键词（检索层过滤：LogQL `!=` 排除其他故障模式的
     *                       特征信号，如非 error-rate 告警排除 "RuntimeException"，
     *                       防止并发故障的异常堆栈污染本告警上下文）
     * @return 日志文本摘要
     */
    public String searchLogsBetween(String service, String keyword, Instant from, Instant to, int limit, String excludeKeyword) {
        try {
            // Loki LogQL: {service="demo-app"} |= "keyword" != "excludeKeyword"
            // 多个 line filter 运算符（|= 包含 / != 排除）可以组合
            StringBuilder logql = new StringBuilder();
            logql.append("{service=\"").append(service).append("\"} |= `").append(keyword).append("`");
            if (excludeKeyword != null && !excludeKeyword.isBlank()) {
                logql.append(" != `").append(excludeKeyword).append("`");
            }

            String start = String.valueOf(from.getEpochSecond()) + "000000000";
            String end = String.valueOf(to.getEpochSecond()) + "000000000";

            String url = lokiUrl + "/loki/api/v1/query_range"
                    + "?query={query}"
                    + "&start={start}"
                    + "&end={end}"
                    + "&limit={limit}";

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class, logql.toString(), start, end, limit);

            if (response == null || !"success".equals(response.get("status"))) {
                return "Loki query failed: " + response;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> streams = (List<Map<String, Object>>) data.get("result");

            if (streams == null || streams.isEmpty()) {
                return "No logs found for service='" + service + "', keyword='" + keyword +
                        "' in correlation window.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== Logs for service=").append(service)
                    .append(", keyword=").append(keyword)
                    .append(" (correlation window: ").append(from).append(" ~ ").append(to).append(") ===\n\n");

            int count = 0;
            for (Map<String, Object> stream : streams) {
                @SuppressWarnings("unchecked")
                List<List<String>> values = (List<List<String>>) stream.get("values");
                if (values != null) {
                    for (List<String> entry : values) {
                        if (count >= limit) break;
                        String timestamp = entry.get(0);
                        String line = entry.get(1);
                        sb.append(formatTimestamp(timestamp)).append(" ").append(line).append("\n");
                        count++;
                    }
                }
            }

            sb.append("\n(Total: ").append(count).append(" log lines)\n");
            log.debug("Loki window search for '{}' in '{}' found {} lines", keyword, service, count);
            this.lastQueryRecord = new QueryRecord(
                    "LOKI", logql.toString(), count + " matching log lines", from, to);
            return sb.toString();

        } catch (Exception e) {
            log.error("Loki window search failed: {}", e.getMessage());
            return "Loki search error: " + e.getMessage();
        }
    }

    /**
     * 获取服务最近的错误日志（ERROR 级别）
     * 用于告警分析时自动收集上下文
     */
    public String getRecentErrors(String service, int minutes) {
        return searchLogs(service, "ERROR", minutes, 20);
    }

    /**
     * 获取服务最近的异常堆栈（exception stack trace）
     */
    public String getRecentExceptions(String service, int minutes) {
        return searchLogs(service, "Exception", minutes, 15);
    }

    private String formatTimestamp(String nanoTimestamp) {
        try {
            long epochSecond = Long.parseLong(nanoTimestamp) / 1_000_000_000;
            return Instant.ofEpochSecond(epochSecond).toString();
        } catch (Exception e) {
            return nanoTimestamp;
        }
    }

    private String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }
}
