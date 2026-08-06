package com.smart.rag.chat.service;

import com.smart.rag.infrastructure.messaging.ConsumerConfig;
import com.smart.rag.infrastructure.messaging.MessageHandler;
import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 聊天消息保存消费者 — 从消息总线拉取 {@code chat_message_save} 并异步落库。
 * <p>
 * PushConsumer 模式（{@link ConsumerConfig#DEFAULT}），消费组 {@link #GROUP}
 * （运维侧 {@code maxDeliveryAttempts=16}）。handler 内部调
 * {@link ChatConversationHelper#saveMessagesAndNotify} 落库。
 * <p>
 * <b>幂等</b>：publisher 端 {@link ChatMessagePublisher} 已为每条消息设置稳定的
 * {@code deduplicationKey}（{@code conversationId + ":" + md5Hex(userMessage)}，见 messaging-bus.md §7.1）；
 * 订阅时由 {@link com.smart.rag.infrastructure.messaging.redis.RedisStreamMessageBus} 自动用
 * {@link com.smart.rag.infrastructure.messaging.idempotent.IdempotentHandler}
 * （Redis SETNX，默认开启、TTL 900s 覆盖 16 次重试窗口）包装 handler，故同一记录的
 * broker 重投递会被去重。这是 at-least-once 语义下的尽力去重：当 Redis 在重投递窗口内
 * 不可用时，{@code IdempotentHandler} 降级为透传，可能产生重复行（见其类注释的权衡声明）
 * ——业务层 DB 唯一约束 {@code (conversation_id, message_index)} 兜底。
 * <p>
 * Phase 0 后消息总线 always-on，本 consumer 始终激活（无条件 {@code @Component}），
 * 与 publisher 端 {@link ChatMessagePublisher} 一致。
 */
@Component
public class ChatMessageSaveConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageSaveConsumer.class);

    /** 消费组（运维侧创建，{@code maxDeliveryAttempts=16}，见 §5.12）。 */
    static final String GROUP = "save-group";

    private final MessageBus messageBus;
    private final ChatConversationHelper conversationHelper;

    private volatile Subscription subscription;
    private volatile boolean running;

    public ChatMessageSaveConsumer(MessageBus messageBus, ChatConversationHelper conversationHelper) {
        this.messageBus = messageBus;
        this.conversationHelper = conversationHelper;
    }

    @Override
    public void start() {
        if (running) return;

        MessageHandler<ChatMessagePayload> handler = msg -> {
            ChatMessagePayload p = msg.payload();
            try {
                // consumer 端 aiResponse 等价物已下沉到 payload.totalTokens；durationMs=0
                // （流式响应耗时已在用量链路记录，落库 duration 仅作文档参考）。
                conversationHelper.saveMessagesAndNotify(
                        p.conversationId(), p.userMessage(), p.assistantContent(),
                        p.candidateId(), (int) p.totalTokens(), 0L);
            } catch (RuntimeException e) {
                // 落库失败：记录可观测日志后重抛，触发 broker 重试。
                // 总线级 Redis SETNX 会拦截相同 deduplicationKey 的重投递，避免无限循环。
                // DB 唯一约束 (conversation_id, message_index) 兜底覆盖 Redis 故障场景。
                log.warn("Chat message save persistence failed (will retry): topic={}, dedupKey={}",
                        ChatMessagePublisher.TOPIC, msg.deduplicationKey(), e);
                throw e;
            }
        };

        subscription = messageBus.subscribe(ChatMessagePublisher.TOPIC, GROUP,
                ConsumerConfig.DEFAULT, ChatMessagePayload.class, handler);
        running = true;
        log.info("Chat message save consumer started: topic={}, group={}",
                ChatMessagePublisher.TOPIC, GROUP);
    }

    @Override
    public void stop() {
        if (!running) return;
        running = false;

        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
        log.info("Chat message save consumer stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return DEFAULT_PHASE - 100;
    }
}
