package com.example.agent.evidence;

/**
 * 可执行证据（executable evidence / citation）—— EVIDENCE INDEX 中的一条。
 * <p>
 * 编号 + 后端记录的真实查询 + 后端生成的可点击深链 URL。
 * LLM 输出中只能引用 [E{n}]；query 与 url 全部由后端映射，
 * LLM 引用了不存在的编号 → resolved=false，不产生任何 query/url。
 */
public record Evidence(
        int index,
        String source,   // PROMETHEUS / LOKI / RUNBOOK
        String query,    // 后端记录的真实查询（LLM 不生成）
        String summary,  // 一行摘要
        String window,   // 时间窗描述
        String url       // 可点击深链（Grafana explore 等）；RUNBOOK 无 url 时为 null
) {
    public static Evidence of(int index, String source, String query, String summary,
                              String window, String url) {
        return new Evidence(index, source, query, summary, window, url);
    }
}
