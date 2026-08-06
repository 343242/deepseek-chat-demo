package com.smart.rag.infrastructure.messaging.redis;

import com.smart.rag.infrastructure.messaging.MessagingMetrics;
import com.smart.rag.infrastructure.messaging.MessagingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link StreamTrimTask} 单测（P1-5，Testcontainers）：MINID 基于 XINFO 最小 last-delivered-id，
 * 积压不丢未投递消息；lag 超 trim-threshold 告警。
 */
class StreamTrimTaskTest extends AbstractRedisStreamTest {

    private static final String TOPIC = "chat_message_save";
    private static final String GROUP = "save-group";

    private StreamTrimTask task;

    @BeforeEach
    void setUp() {
        flushAll();
        task = new StreamTrimTask(connections(), props(), metrics());
        task.register(TOPIC, GROUP);
    }

    private void createGroup() {
        connections().executeCommand("XGROUP", "CREATE", keys().streamKey(TOPIC), GROUP, "$", "MKSTREAM");
    }

    private void addMessages(int count) {
        for (int i = 0; i < count; i++) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("topic", TOPIC);
            fields.put("payload", "\"m" + i + "\"");
            fields.put("attempt", "0");
            business().opsForStream().add(MapRecord.create(keys().streamKey(TOPIC), fields));
        }
    }

    @Test
    @DisplayName("MINID 裁剪只删各组已读过的区间：未投递消息保留")
    void trim_keepsUndeliveredMessages() {
        createGroup();
        // 250 条横跨多个 listpack node（默认 stream-node-max-entries=100）——
        // MINID ~（approx）只整 node 裁剪，单 node 小流不裁（Redis streamTrim KEEPREF 语义）
        addMessages(250);
        // 消费前 200 条并 XACK（last-delivered-id = 第 200 条）
        List<MapRecord<String, Object, Object>> read = connections().streamOps().read(
            Consumer.from(GROUP, "app:test"),
            StreamReadOptions.empty().count(200).block(Duration.ofMillis(200)),
            StreamOffset.create(keys().streamKey(TOPIC), ReadOffset.lastConsumed()));
        assertEquals(200, read.size());
        for (MapRecord<String, Object, Object> r : read) {
            connections().streamOps().acknowledge(keys().streamKey(TOPIC), GROUP, r.getId());
        }

        task.trim();

        // node1（1-100 条，整 node 低于 minid）被裁；未投递的 201-250 条保留
        long remaining = business().opsForStream().size(keys().streamKey(TOPIC));
        assertEquals(150, remaining);
        // 未投递的 50 条仍在 → 后续消费可全部收到
        List<MapRecord<String, Object, Object>> tail = connections().streamOps().read(
            Consumer.from(GROUP, "app:test2"),
            StreamReadOptions.empty().count(100).block(Duration.ofMillis(200)),
            StreamOffset.create(keys().streamKey(TOPIC), ReadOffset.lastConsumed()));
        assertEquals(50, tail.size());
    }

    @Test
    @DisplayName("group 从未消费（last-delivered=0-0）→ 不裁剪任何 entry")
    void trim_neverDelivered_noTrim() {
        createGroup();
        addMessages(10);

        task.trim();

        assertEquals(10, business().opsForStream().size(keys().streamKey(TOPIC)));
    }

    @Test
    @DisplayName("lag（XLEN − XPENDING）超 trim-threshold → 告警 counter")
    void lagExceedsThreshold_alerts() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MessagingProperties alertProps = customProps(new MessagingProperties.RedisStreamConfig(
            null, null, null, null, null, 3, 0, null, 0, null, null, null, 0, null, null, null));
        StreamTrimTask alertTask = new StreamTrimTask(connections(), alertProps, new MessagingMetrics(registry));
        alertTask.register(TOPIC, GROUP);
        createGroup();
        addMessages(10);   // 无人消费 → lag = 10 > 3

        alertTask.trim();

        double count = registry.get("messaging.stream.trim.threshold.exceeded").counter().count();
        assertEquals(1.0, count);
    }

    @Test
    @DisplayName("未注册的 topic 不裁剪")
    void unregisteredTopic_notTrimmed() {
        createGroup();
        addMessages(5);
        task.unregister(TOPIC, GROUP);

        task.trim();

        assertEquals(5, business().opsForStream().size(keys().streamKey(TOPIC)));
    }
}
