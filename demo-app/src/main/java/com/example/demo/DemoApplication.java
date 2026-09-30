package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Demo e-commerce application.
 * <p>
 * The monitored sample service: it emits metrics and logs for the AI agent to
 * analyze, and exposes deliberate fault-injection endpoints to trigger alerts.
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
