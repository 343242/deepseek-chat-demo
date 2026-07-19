package com.smart.rag.agent.advisor;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 反思标记语法 — LLM 在响应文本中输出的 XML 风格标记，封装 Self-RAG/DeepRAG 的结构化信号。
 * <p>
 * 设计文档 §2.4 的工程化路径：闭源 LLM 无法 SFT/训练 reflection token（Self-RAG/DeepRAG 论文依赖），
 * 改为让 LLM 在自然语言响应中输出 XML 标记包裹的 JSON，由 {@link ReflectionParsingAdvisor} 解析落地。
 * <p>
 * 三种标记：
 * <ul>
 *   <li>{@link #ATOMIC_DECISION} — DeepRAG 原子决策（retrieve / parametric），§2.4.1</li>
 *   <li>{@link #REFLECTION} — Self-RAG 自省（isRelevant/isSufficient/nextAction），§2.4.2</li>
 *   <li>{@link #INTERMEDIATE_ANSWER} — DeepRAG 中间答案，§2.4.3</li>
 * </ul>
 * <p>
 * 标记格式示例：
 * <pre>{@code
 * <reflection>
 * {"isRelevant": true, "isSufficient": false, "missingAspects": ["..."], "nextAction": "rewrite_and_search"}
 * </reflection>
 * }</pre>
 * <p>
 * 提取规则：非贪婪匹配标记内首个 JSON 对象（从首个 {@code "{"} 到对应闭合的 {@code "}"}）；
 * 同一标记多次出现只取第一个（避免 LLM 重复输出污染）；标记未闭合或无 JSON 时返回 empty。
 */
public enum ReflectionMarker {

    ATOMIC_DECISION("atomic_decision"),
    REFLECTION("reflection"),
    INTERMEDIATE_ANSWER("intermediate_answer");

    private final String tagName;

    ReflectionMarker(String tagName) {
        this.tagName = tagName;
    }

    /** 开标记，如 {@code <reflection>} */
    public String openTag() {
        return "<" + tagName + ">";
    }

    /** 闭标记，如 {@code </reflection>} */
    public String closeTag() {
        return "</" + tagName + ">";
    }

    /**
     * 从文本中提取本标记包裹的 JSON 对象内容。
     * <p>
     * 匹配首个开标记到首个闭标记之间的片段，再从中提取首个 {@code "{"} 到最后个 {@code "}"}
     * 的子串（容忍标记内 JSON 前后的空白/解释文字）。同一标记多次出现只取第一个。
     *
     * @param text LLM 响应文本（null/blank 返回 empty）
     * @return 提取出的 JSON 字符串；标记不存在或无 JSON 时返回 empty
     */
    public Optional<String> extract(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        // 非贪婪匹配首个开-闭标记对；DOTALL 允许 JSON 跨行
        Pattern p = Pattern.compile(Pattern.quote(openTag()) + "(.*?)" + Pattern.quote(closeTag()),
            Pattern.DOTALL);
        Matcher m = p.matcher(text);
        if (!m.find()) {
            return Optional.empty();
        }
        String inner = m.group(1);
        int start = inner.indexOf('{');
        int end = inner.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        return Optional.of(inner.substring(start, end + 1));
    }

    /**
     * 从文本中剥离本标记对（含标记内的 JSON），返回清理后的文本。
     * <p>
     * 用于 after() 写回 response 时去除标记污染，避免进入最终答案和 ChatMemory。
     * 剥离后多余空行折叠为单个换行。
     *
     * @param text 原始文本
     * @return 剥离本标记后的文本；标记不存在时原样返回
     */
    public String strip(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        Pattern p = Pattern.compile(Pattern.quote(openTag()) + ".*?" + Pattern.quote(closeTag()),
            Pattern.DOTALL);
        return p.matcher(text).replaceAll("");
    }
}
