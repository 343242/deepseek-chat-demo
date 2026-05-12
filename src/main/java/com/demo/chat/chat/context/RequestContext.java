package com.demo.chat.chat.context;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 请求上下文 — CAG 的核心数据结构
 * <p>
 * 聚合三类运行时信号：用户画像、会话状态、策略约束。
 * 不可变对象（record），线程安全，一次构建全程使用。
 * <p>
 * 安全边界：{@link #toPromptSegment()} 只输出昵称和角色名，
 * 不输出 permissions、userId、email 等敏感字段。
 */
public record RequestContext(
        UserContext user,
        SessionContext session,
        PolicyContext policy
) {

    /**
     * 生成注入 LLM system prompt 的上下文文本段
     *
     * @return 可直接注入 prompt 的文本，null 安全
     */
    public String toPromptSegment() {
        StringBuilder sb = new StringBuilder();
        if (user != null) {
            sb.append("当前用户：").append(sanitize(user.nickname()))
                    .append("，角色：").append(user.roles().stream()
                            .map(RequestContext::sanitize)
                            .collect(Collectors.joining("、")));
        }
        if (session != null && session.stage() != null) {
            sb.append("\n对话阶段：").append(sanitize(session.stage()));
        }
        if (policy != null && !policy.constraints().isEmpty()) {
            sb.append("\n回答约束：\n");
            policy.constraints().forEach(c -> sb.append("- ").append(sanitize(c)).append("\n"));
        }
        return sb.toString();
    }

    /**
     * 生成检索阶段的额外提示（预留，当前版本不使用）
     */
    public Map<String, Object> toRetrievalHints() {
        return Map.of();
    }

    /**
     * 清理注入文本，防止间接 prompt injection。
     * <p>
     * 防御纵深：即使角色名由管理员设置（非用户输入），
     * 注入 LLM 的文本也应做基本清理。
     * <ul>
     *   <li>移除 ASCII 控制字符 (U+0000–U+001F, U+007F)</li>
     *   <li>移除零宽字符、行/段分隔符、BOM (U+200B–200F, U+2028, U+2029, U+FEFF)</li>
     *   <li>移除特殊空白字符 (U+00A0, U+2000–U+200A)</li>
     *   <li>换行替换为空格，折叠连续空白</li>
     *   <li>长度限制 200 字符（防止 token 耗尽）</li>
     * </ul>
     */
    static String sanitize(String input) {
        if (input == null) return "";
        String cleaned = input
                .replaceAll("[\\x00-\\x1F\\x7F]", "")
                .replaceAll("[\\u200B-\\u200F\\u2028\\u2029\\uFEFF\\u00A0\\u2000-\\u200A]", "")
                .replaceAll("[\\r\\n]", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }
}
