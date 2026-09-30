package com.example.agent.evidence;

import java.time.Instant;

/**
 * 工具实际执行过的查询记录 —— 可执行证据（executable evidence）的原始来源。
 * <p>
 * 由工具（PrometheusToolService / LokiToolService）在每次真实查询成功后记录，
 * AlertAnalysisService 据此构建 EVIDENCE INDEX 并映射为可点击的 citation。
 * <p>
 * 关键设计（v9，证据链可执行化）：
 * 查询字符串（PromQL / LogQL）永远由后端工具生成并记录，LLM 从不参与生成——
 * LLM 只能引用 EVIDENCE INDEX 中的编号，因此无法伪造 query 或 URL。
 */
public record QueryRecord(
        String source,   // PROMETHEUS / LOKI
        String query,    // 真实执行且成功的查询（PromQL / LogQL）
        String summary,  // 结果一行摘要（如 "3 matching log lines" / "1 series"）
        Instant from,    // 查询时间窗起点（null = 瞬时查询）
        Instant to       // 查询时间窗终点（null = 瞬时查询）
) {
    public String window() {
        if (from == null || to == null) return "instant";
        return from + " ~ " + to;
    }
}
