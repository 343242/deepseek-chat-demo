package com.smart.rag.evaluation.metrics.generation;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 检索片段 → LLM prompt 文本的统一拼接（"片段N：\n内容\n\n"）。
 * 原先在 5 处 Scorer/Calculator 中重复的同一段循环。
 */
public final class ContextTextBuilder {

    private ContextTextBuilder() {
    }

    public static String build(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            sb.append("片段").append(i + 1).append("：\n");
            sb.append(docs.get(i).getText()).append("\n\n");
        }
        return sb.toString();
    }
}
