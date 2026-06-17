package com.smart.rag.rag.etl;

import com.smart.rag.infrastructure.messaging.ConsumerConfig;
import com.smart.rag.infrastructure.messaging.ConsumerMode;
import com.smart.rag.infrastructure.messaging.MessageHandler;
import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.RetryPolicy;
import com.smart.rag.infrastructure.messaging.Subscription;
import com.smart.rag.rag.event.EtlCompletedEvent;
import com.smart.rag.rag.service.EtlDispatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * ETL 文档消费者 — 从消息总线拉取 RAG 索引任务并执行 ETL 处理。
 * <p>
 * 使用 SimpleConsumer 模式：LLM 调用（embedding + chunking）耗时不可预测，
 * SimpleConsumer 无消费超时概念，通过 invisibleDuration 控制失败后重新可见时间。
 * <p>
 * Phase 0 后消息总线 always-on，本 consumer 始终激活（无条件 {@code @Component}）。
 */
@Component
public class EtlDocumentConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(EtlDocumentConsumer.class);

    public static final String TOPIC = "rag_index_document";
    static final String GROUP = "index-group";

    private static final ConsumerConfig CONSUMER_CONFIG = ConsumerConfig.builder()
        .consumerMode(ConsumerMode.SIMPLE)
        .batchSize(5)
        .invisibleDuration(Duration.ofMinutes(30))
        .retryPolicy(RetryPolicy.SIMPLE_DEFAULT)
        .build();

    private final MessageBus messageBus;
    private final EtlDispatchService etlDispatchService;
    private final ApplicationEventPublisher eventPublisher;

    private volatile Subscription subscription;
    private volatile boolean running;

    public EtlDocumentConsumer(MessageBus messageBus,
                               EtlDispatchService etlDispatchService,
                               ApplicationEventPublisher eventPublisher) {
        this.messageBus = messageBus;
        this.etlDispatchService = etlDispatchService;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void start() {
        if (running) return;

        MessageHandler<EtlCandidate> handler = msg -> {
            EtlCandidate candidate = msg.payload();
            log.info("ETL consumer received: documentId={}, file={}", candidate.documentId(), candidate.fileName());

            List<EtlResult> results = etlDispatchService.dispatch(List.of(candidate));

            if (!results.isEmpty() && EtlStatus.COMPLETED.equals(results.getFirst().status())) {
                eventPublisher.publishEvent(
                    new EtlCompletedEvent(candidate.documentId(), candidate.userId(), candidate.teamId()));
            }
        };

        subscription = messageBus.subscribe(TOPIC, GROUP, CONSUMER_CONFIG, EtlCandidate.class, handler);
        running = true;
        log.info("ETL document consumer started: topic={}, group={}", TOPIC, GROUP);
    }

    @Override
    public void stop() {
        if (!running) return;
        running = false;

        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
        log.info("ETL document consumer stopped");
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
