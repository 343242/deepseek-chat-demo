package com.smart.rag.evaluation.util;

import java.util.regex.Pattern;

/**
 * JSON 提取工具类
 * <p>
 * 三层容错策略：raw → ```json``` 代码块 → {}/[] 正则提取。
 * 从 LlmJudgeImpl 提取为公共工具类，供评估模块各 Scorer 复用。
 * </p>
 */
public final class JsonExtractorUtil {

    /** ```json / ``` 代码块均可，且不要求标签后换行（```` ```json{...}```` 同行写法也能匹配） */
    private static final Pattern MARKDOWN_JSON = Pattern.compile("```json?\\s*([\\s\\S]*?)```");

    private JsonExtractorUtil() {}

    /**
     * 从 LLM 响应中提取 JSON 字符串
     * <ol>
     *   <li>如果已经是 JSON（以 { 或 [ 开头），直接返回</li>
     *   <li>尝试提取 ```json ... ``` markdown 代码块</li>
     *   <li>尝试提取最外层 { ... } 或 [ ... ]</li>
     * </ol>
     *
     * @param raw LLM 原始响应
     * @return 提取到的 JSON 字符串
     */
    public static String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw != null ? raw.trim() : "";
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }
        // markdown code block
        var matcher = MARKDOWN_JSON.matcher(raw);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        // { ... }
        int startBrace = raw.indexOf('{');
        int endBrace = raw.lastIndexOf('}');
        if (startBrace >= 0 && endBrace > startBrace) {
            return raw.substring(startBrace, endBrace + 1);
        }
        // [ ... ]
        int startBracket = raw.indexOf('[');
        int endBracket = raw.lastIndexOf(']');
        if (startBracket >= 0 && endBracket > startBracket) {
            return raw.substring(startBracket, endBracket + 1);
        }
        return trimmed;
    }
}
