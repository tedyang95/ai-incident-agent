package com.example.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AI Incident Triage Agent
 *
 * 核心流程（workflow）：
 * 1. Alertmanager 通过 webhook 把告警发进来
 * 2. Agent 提取告警关键信息（severity, service, alertname, description）
 * 3. 调用工具收集上下文：
 *    - queryMetrics() → 从 Prometheus 查相关指标
 *    - searchLogs() → 从 Loki 搜相关日志
 *    - getRecentDeploys() → 查最近部署（未来扩展）
 *    - searchRunbooks() → RAG 检索 runbook 知识库
 * 4. LLM 基于所有上下文生成结构化根因分析（root cause analysis）
 * 5. 存入数据库，通过 API / 前端展示
 * 6. （未来）自动创建 ticket / 发通知 / 建议修复动作
 *
 * 对应 AI 产品六层框架：
 * - L2 Grounded AI (RAG) → 检索 runbook
 * - L3 Tool-using AI → 调用 Prometheus/Loki API
 * - L4 LLM Workflow → 固定的告警分析流程
 * - （目标）L5 Agentic Core → 多轮循环排查
 */
@SpringBootApplication
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
