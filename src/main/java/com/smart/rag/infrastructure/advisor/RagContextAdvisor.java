package com.smart.rag.infrastructure.advisor;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 动态上下文 Advisor — 把 CAG 段 + {@code <<REF>>} 检索块作为动态 {@link SystemMessage}
 * 注入对话历史之后、当前问题之前（动态尾，见 design §0.1）。
 * <p>
 * 两条保证（design §2.8b）：
 * <ol>
 *   <li>记忆安全（role 级）：动态以 SystemMessage 注入；{@code MessageChatMemoryAdvisor}
 *       （Spring AI 1.1.6）只持久化 user+assistant，system 永不入库 → Redis 记忆不污染，
 *       与 advisor 顺序无关。</li>
 *   <li>缓存友好（位置级）：动态 SystemMessage 插在历史之后、当前问题之前 →
 *       {@code [静态基座 + 历史]} 仍是稳定/append-only 前缀 → 命中；仅动态尾 miss。</li>
 * </ol>
 * per-request 实例（携带本轮 cagSegment/refBlock），随 buildAdvisorChain 之后加入 chain。
 */
public class RagContextAdvisor implements BaseAdvisor {

    /**
     * 排在 MessageChatMemoryAdvisor（默认 order=0）之后：确保历史已 load 再插入动态尾，
     * 且 RagContextAdvisor 的动态 SystemMessage 不被 memory 持久化（system 角色）。
     */
    private static final int ORDER = 100;

    private final @Nullable String cagSegment;
    private final @Nullable String refBlock;

    public RagContextAdvisor(@Nullable String cagSegment, @Nullable String refBlock) {
        this.cagSegment = cagSegment;
        this.refBlock = refBlock;
    }

    @Override
    @NonNull
    public String getName() {
        return "RagContextAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    @NonNull
    public ChatClientRequest before(@NonNull ChatClientRequest request, @NonNull AdvisorChain chain) {
        String dynamic = composeDynamic();
        if (dynamic == null || dynamic.isBlank()) {
            return request;
        }
        List<Message> msgs = new ArrayList<>(request.prompt().getInstructions());
        int insertAt = msgs.size(); // 默认末尾
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i) instanceof UserMessage) {
                insertAt = i; // 当前问题之前（历史之后）
                break;
            }
        }
        msgs.add(insertAt, new SystemMessage(dynamic));
        return request.mutate()
            .prompt(new Prompt(msgs, request.prompt().getOptions()))
            .build();
    }

    @Override
    @NonNull
    public ChatClientResponse after(@NonNull ChatClientResponse response, @NonNull AdvisorChain chain) {
        return response;
    }

    private @Nullable String composeDynamic() {
        StringBuilder sb = new StringBuilder();
        if (cagSegment != null && !cagSegment.isBlank()) {
            sb.append(cagSegment).append("\n\n");
        }
        if (refBlock != null && !refBlock.isBlank()) {
            sb.append(refBlock);
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
