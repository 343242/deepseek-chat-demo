package com.smart.rag.infrastructure.llm.adapter;

import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import com.smart.rag.infrastructure.llm.usage.UsageContext;
import com.smart.rag.infrastructure.llm.usage.UsageEventSink;
import com.smart.rag.infrastructure.llm.usage.UsageScene;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * ChatModel/ChatClient 的唯一装配点。
 * <p>
 * 装饰栈层序（{@code UsageRecordingChatModel(ChatModelAdapter(capable))}）只在此定义一次；
 * 上层（chat / agent）不再自行 {@code new ChatModelAdapter(...)}。每请求构造一次装饰器实例——
 * per-call 状态（计时、轮间 usage 累计、归因上下文）的天然归宿；真正昂贵的厂商客户端
 * 由 {@link LlmClientRegistry} 快照缓存复用，此处 {@code new} 的只是无状态桥接壳。
 * <p>
 * 候选不存在时由 {@link LlmClientRegistry#get} 抛 {@code RemoteException}（fail-fast，不静默降级）。
 */
@Component
public class ChatModelAssembler {

    private final LlmClientRegistry registry;
    private final UsageEventSink usageSink;

    public ChatModelAssembler(LlmClientRegistry registry, UsageEventSink usageSink) {
        this.registry = registry;
        this.usageSink = usageSink;
    }

    /**
     * 装配带用量采集装饰器的 ChatModel。
     *
     * @param userId         发起用户（用量归因）
     * @param candidateId    候选模型 ID（registry candidate ID）
     * @param scene          用量场景
     * @param conversationId 会话 ID，无会话语境可传 {@code null}
     */
    public UsageRecordingChatModel chatModel(Long userId,
                                             String candidateId,
                                             UsageScene scene,
                                             @Nullable String conversationId) {
        ChatCapable capable = registry.get(candidateId, ChatCapable.class);
        UsageContext context = new UsageContext(userId, candidateId, scene, conversationId);
        return new UsageRecordingChatModel(new ChatModelAdapter(capable), context, usageSink);
    }

    /** 装配带用量采集装饰器的 ChatClient（阻塞/流式聊天主链路）。 */
    public ChatClient chatClient(Long userId,
                                 String candidateId,
                                 UsageScene scene,
                                 @Nullable String conversationId) {
        return ChatClient.builder(chatModel(userId, candidateId, scene, conversationId)).build();
    }

    /**
     * 把已装配的 ChatModel 包成 ChatClient（Agent 链路：护栏与 ChatClient 必须共享同一装饰器实例，
     * 经此入口装配，禁止再对同一候选二次 {@link #chatModel} 造成双实例）。
     */
    public ChatClient chatClient(UsageRecordingChatModel model) {
        return ChatClient.builder(model).build();
    }
}
