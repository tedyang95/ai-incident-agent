package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Demo E-commerce Application
 * 被监控的示例应用，产生指标（metrics）和日志（logs）供 AI Agent 分析。
 * 包含故意设计的故障注入（fault injection）endpoint，用于触发告警。
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
