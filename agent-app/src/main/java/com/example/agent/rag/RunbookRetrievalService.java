package com.example.agent.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runbook 检索服务（RAG - Retrieval Augmented Generation）
 *
 * 当前实现：基于关键词的简单检索（keyword-based retrieval）
 * 未来升级：pgvector 向量检索（vector embedding），实现语义搜索
 *
 * 对应 AI 产品六层框架中的 L2: Grounded AI / RAG
 * 面试中可以说："I implemented a RAG system that retrieves relevant runbooks
 * based on alert context, starting with keyword-based retrieval and planning
 * to upgrade to pgvector semantic search."
 */
@Service
public class RunbookRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RunbookRetrievalService.class);

    private static final String RUNBOOKS_DIR = "runbooks";
    private static final int MAX_RESULTS = 3;
    private static final int MAX_CHARS_PER_RUNBOOK = 2000;

    /**
     * 根据告警信息检索相关的 runbook
     *
     * @param alertname  告警名称
     * @param service    服务名
     * @param category   告警类别
     * @param description 告警描述
     * @return 匹配的 runbook 文本摘要
     */
    public String retrieve(String alertname, String service, String category, String description) {
        List<Path> runbookFiles = listRunbookFiles();
        if (runbookFiles.isEmpty()) {
            return "No runbooks found in " + RUNBOOKS_DIR + " directory.";
        }

        // 构建搜索关键词
        String searchText = (alertname + " " + service + " " + category + " " + description).toLowerCase();
        String[] keywords = searchText.split("\\s+");

        // 简单评分：统计每个 runbook 中关键词出现的次数
        List<ScoredRunbook> scored = new ArrayList<>();
        for (Path file : runbookFiles) {
            try {
                String content = Files.readString(file).toLowerCase();
                int score = 0;
                for (String keyword : keywords) {
                    if (keyword.length() > 2) { // 忽略太短的词
                        int count = countOccurrences(content, keyword);
                        score += count;
                    }
                }
                // 标题匹配加分
                String filename = file.getFileName().toString().toLowerCase();
                for (String keyword : keywords) {
                    if (keyword.length() > 2 && filename.contains(keyword)) {
                        score += 5;
                    }
                }

                if (score > 0) {
                    scored.add(new ScoredRunbook(file, score, Files.readString(file)));
                }
            } catch (IOException e) {
                log.warn("Failed to read runbook {}: {}", file, e.getMessage());
            }
        }

        if (scored.isEmpty()) {
            return "No matching runbooks found for: " + alertname + " / " + service;
        }

        // 按分数排序，取前 N 个
        scored.sort(Comparator.comparingInt(ScoredRunbook::score).reversed());

        StringBuilder sb = new StringBuilder();
        sb.append("=== Matched Runbooks (top ").append(Math.min(MAX_RESULTS, scored.size())).append(") ===\n\n");

        for (int i = 0; i < Math.min(MAX_RESULTS, scored.size()); i++) {
            ScoredRunbook sr = scored.get(i);
            String content = sr.content();
            if (content.length() > MAX_CHARS_PER_RUNBOOK) {
                content = content.substring(0, MAX_CHARS_PER_RUNBOOK) + "... [truncated]";
            }
            sb.append("--- Runbook #").append(i + 1)
                    .append(" (score=").append(sr.score())
                    .append(", file=").append(sr.file().getFileName()).append(") ---\n");
            sb.append(content).append("\n\n");
        }

        log.info("Retrieved {} runbooks for alert '{}' (top score: {})",
                scored.size(), alertname, scored.get(0).score());
        return sb.toString();
    }

    private List<Path> listRunbookFiles() {
        List<Path> files = new ArrayList<>();
        Path dir = Paths.get(RUNBOOKS_DIR);
        if (!Files.exists(dir)) {
            log.warn("Runbooks directory not found: {}", dir.toAbsolutePath());
            return files;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md"))
                    .forEach(files::add);
        } catch (IOException e) {
            log.error("Failed to list runbooks: {}", e.getMessage());
        }
        return files;
    }

    private int countOccurrences(String text, String keyword) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(keyword, idx)) != -1) {
            count++;
            idx += keyword.length();
        }
        return count;
    }

    private record ScoredRunbook(Path file, int score, String content) {}
}
