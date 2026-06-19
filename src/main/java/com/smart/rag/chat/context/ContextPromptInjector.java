package com.smart.rag.chat.context;

import org.springframework.stereotype.Component;

/**
 * 上下文 Prompt 注入器
 * <p>
 * 将 {@link RequestContext} 转化为文本段，注入到 system prompt 中。
 * 注入位置：在原有 system prompt 之前，用明确的标记分隔。
 * <p>
 * 受 {@link CagProperties#isInjectPrompt()} 开关控制。
 */
@Component
public class ContextPromptInjector {

    private final CagProperties cagProperties;

    public ContextPromptInjector(CagProperties cagProperties) {
        this.cagProperties = cagProperties;
    }

    /**
     * 注入上下文到 system prompt
     *
     * @param originalPrompt 原有 system prompt（可能为 null 或空）
     * @param context        请求上下文（可能为 null，表示 CAG 未启用或构建失败）
     * @return 增强后的 system prompt；CAG 关闭或上下文为空时返回原始 prompt
     */
    public String inject(String originalPrompt, RequestContext context) {
        if (!cagProperties.isInjectPrompt()) {
            return originalPrompt;
        }

        if (context == null) {
            return originalPrompt;
        }

        String contextSegment = context.toPromptSegment();
        if (contextSegment == null || contextSegment.isBlank()) {
            return originalPrompt;
        }

        String basePrompt = (originalPrompt != null && !originalPrompt.isBlank())
                ? originalPrompt : "你是一个 AI 助手。";

        return """
                [用户上下文]
                %s
                
                [系统指令]
                %s
                """.formatted(contextSegment, basePrompt);
    }

    /**
     * 仅生成 CAG 上下文段（不含系统指令基座），供动态尾注入（RagContextAdvisor）。
     * <p>
     * 受 {@link CagProperties#isInjectPrompt()} 开关控制；CAG 关闭或上下文为空时返回 null。
     *
     * @param context 请求上下文（可能为 null）
     * @return CAG 段文本（含 [用户上下文] 标记），或 null
     */
    public String cagSegment(RequestContext context) {
        if (!cagProperties.isInjectPrompt() || context == null) {
            return null;
        }
        String segment = context.toPromptSegment();
        if (segment == null || segment.isBlank()) {
            return null;
        }
        return "[用户上下文]\n" + segment;
    }
}
