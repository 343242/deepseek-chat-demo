package com.smart.rag.infrastructure.messaging.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.messaging.ConsumerConfig;
import com.smart.rag.infrastructure.messaging.ConsumerMode;
import com.smart.rag.infrastructure.messaging.JacksonMessageCodec;
import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import com.smart.rag.infrastructure.messaging.MessagingProperties;
import com.smart.rag.infrastructure.messaging.Subscription;
import com.smart.rag.rag.etl.ImageExtractJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 验收 3（design §11）——图片任务崩溃恢复 + 消息不设 dedupKey 的回归锁定
 * （Testcontainers redis:8，短 pel-min-idle 加速——生产默认 40min，等价语义）。
 * <p>
 * 场景 = "消费者进程在已读未 ACK 窗口被杀"：
 * <ol>
 *   <li>两条 {@code ImageExtractJob} 消息入 stream，dead consumer XREADGROUP 读走不 XACK
 *       （等价 kill -9：消息停留 PEL，无人处理）；</li>
 *   <li>对带 dedupKey 的消息预置 SETNX 标记（模拟 IdempotentHandler"已标记、未完成"
 *       的崩溃窗口——v1.6 严重-2 的故障形态）；无 dedupKey 消息天然豁免；</li>
 *   <li>新消费者（bus.subscribe，含 IdempotentHandler.wrap）启动 + PelRecoverySweeper
 *       XAUTOCLAIM 重投；</li>
 *   <li>断言：<b>无 dedupKey 的图片消息被重新消费完成</b>（重投恢复承诺成立）；
 *       <b>带 dedupKey 的消息被 SETNX 判重静默跳过且 ACK</b>（若 rag_extract_images
 *       沿用 dedupKey=documentId 先例，崩溃重投恢复即被击穿——本测试是该决策的对照证明）。</li>
 * </ol>
 */
class ImageExtractCrashRecoveryTest extends AbstractRedisStreamTest {

    private static final String TOPIC = "rag_extract_images";
    private static final String GROUP = "image-group";
    private static final long PEL_MIN_IDLE_MS = 1000;

    private final AtomicInteger handlerCalls = new AtomicInteger();
    private final ConcurrentLinkedQueue<ImageExtractJob> received = new ConcurrentLinkedQueue<>();
    private MessageBus bus;
    private PelRecoverySweeper fastSweeper;
    private Subscription subscription;

    @BeforeEach
    void setUp() {
        flushAll();
        handlerCalls.set(0);
        received.clear();
    }

    @AfterEach
    void tearDown() {
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
        if (bus != null) {
            bus.shutdown();
            bus = null;
        }
    }

    @Test
    @DisplayName("崩溃（未 ACK）后重投：无 dedupKey 图片消息被重新消费；带 dedupKey 的被 SETNX 判重吞掉")
    void crashRecovery_redeliversImageJob_withoutDedupKey_andProvesDedupKeyHazard() throws Exception {
        String streamKey = keys().streamKey(TOPIC);
        quietCreateGroup(streamKey);

        // 生产者 = 真实 send 路径（ImageManifestService.envelope 同形态：
        // hashKey=documentId、dedupKey=null；对照组 B 模拟旧先例 dedupKey=documentId）
        bus = buildBus();
        ImageExtractJob jobA = new ImageExtractJob(42L, "docs", "orig/42.pdf", "manual.pdf");
        ImageExtractJob jobB = new ImageExtractJob(43L, "docs", "orig/43.pdf", "report.pdf");
        bus.send(envelope(jobA, null));
        bus.send(envelope(jobB, String.valueOf(jobB.documentId())));

        // kill -9 等价：dead consumer 读走不 ACK → 两条留 PEL
        List<MapRecord<String, Object, Object>> read = connections().streamOps().read(
                Consumer.from(GROUP, "app:dead-consumer"),
                StreamReadOptions.empty().count(10).block(Duration.ofMillis(200)),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()));
        assertEquals(2, read.size(), "两条消息应被 dead consumer 读入 PEL");
        assertEquals(2, connections().streamOps().pending(streamKey, GROUP).getTotalPendingMessages());

        // 崩溃窗口模拟：B 的幂等标记已 SETNX（IdempotentHandler 已标记、handler 未完成即死）
        business().opsForValue().set(idempotentKey(jobB.documentId()), "1", Duration.ofSeconds(900));

        // 崩溃恢复：新实例启动订阅（含 IdempotentHandler.wrap），短 minIdle sweeper 接管重投
        subscription = bus.subscribe(TOPIC, GROUP, ConsumerConfig.builder()
                        .consumerMode(ConsumerMode.SIMPLE).batchSize(1).concurrency(1).build(),
                ImageExtractJob.class,
                msg -> {
                    handlerCalls.incrementAndGet();
                    received.add(msg.payload());
                });
        fastSweeper.register(runnerOf(subscription));

        Thread.sleep(PEL_MIN_IDLE_MS + 200);
        fastSweeper.drain();

        // 核心断言：A（无 dedupKey）经 XAUTOCLAIM 重投后完成消费
        await().atMost(15, java.util.concurrent.TimeUnit.SECONDS)
                .untilAsserted(() -> assertEquals(1, handlerCalls.get(),
                        "仅无 dedupKey 的消息应被真正处理"));
        assertEquals(jobA, received.peek(), "被处理的是 A（图片消息形态）");
        // B 被 SETNX 判重静默跳过 + 自动 ACK —— 消息离开 PEL 但 handler 未执行
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
                .untilAsserted(() -> assertEquals(0,
                        connections().streamOps().pending(streamKey, GROUP).getTotalPendingMessages(),
                        "PEL 应清空（B 静默 ACK、A 正常 ACK）"));
    }

    // ==================== helpers ====================

    private MessageBus buildBus() {
        MessagingProperties pelProps = customProps(new MessagingProperties.RedisStreamConfig(
                null, null, null, null, null, 0, 0, null, 0, Duration.ofMillis(PEL_MIN_IDLE_MS),
                null, null, 0, null, null, null));
        fastSweeper = new PelRecoverySweeper(connections(), pelProps, metrics());
        return new RedisStreamMessageBus(props(), business(),
                new JacksonMessageCodec(new ObjectMapper()), null, null, null,
                connections(), keys(), dlqWriter(),
                new RetrySweeper(delayQueue(), keys(), backoff(), props(), metrics(), dlqWriter()),
                fastSweeper,
                new StreamTrimTask(connections(), props(), metrics()));
    }

    /** 与 ImageManifestService.envelope 同形态：hashKey=documentId，dedupKey 由参数控制 */
    private static MessageEnvelope<ImageExtractJob> envelope(ImageExtractJob job, String dedupKey) {
        return new MessageEnvelope<>(null, TOPIC, null, job,
                String.valueOf(job.documentId()), dedupKey, Map.of(), System.currentTimeMillis());
    }

    private static String idempotentKey(Long documentId) {
        return "messaging:idempotent:" + TOPIC + ":" + documentId;
    }

    private void quietCreateGroup(String streamKey) {
        try {
            connections().executeCommand("XGROUP", "CREATE", streamKey, GROUP, "$", "MKSTREAM");
        } catch (Exception e) {
            // BUSYGROUP tolerated
        }
    }

    private static RedisStreamConsumerRunner<?> runnerOf(Subscription subscription) {
        try {
            var field = subscription.getClass().getDeclaredField("runner");
            field.setAccessible(true);
            return (RedisStreamConsumerRunner<?>) field.get(subscription);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot access runner from subscription", e);
        }
    }
}
