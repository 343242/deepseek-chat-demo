package com.smart.rag.infrastructure.messaging.outbox;

import com.smart.rag.infrastructure.messaging.BackoffSchedule;
import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.MessagePayloadCodec;
import com.smart.rag.infrastructure.messaging.MessagingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * OutboxRelay Leader Election 测试（design §3.1，真实 Redis + Redisson）——
 * 仅 leader 实例跑 drain；leader 正常 stop() 立即让位（不等看门狗）；leader 崩溃
 * （客户端死亡，看门狗停止续约）→ lease 到期后 follower 接管。
 */
@Testcontainers
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxRelayLeaderTest extends AbstractOutboxTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8.2.6-bookworm"))
        .withExposedPorts(6379)
        .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    @Mock
    private MessageBus delegate;

    private MessagePayloadCodec codec;
    private final AtomicInteger delivered = new AtomicInteger();

    @BeforeEach
    void setUp() {
        clearOutbox();
        codec = new com.smart.rag.infrastructure.messaging.JacksonMessageCodec(
            new com.fasterxml.jackson.databind.ObjectMapper());
        delivered.set(0);
        when(delegate.send(any())).thenAnswer(inv -> "id-" + delivered.incrementAndGet());
    }

    private RedissonClient newClient(long watchdogMillis) {
        Config config = new Config();
        config.useSingleServer()
            .setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        if (watchdogMillis > 0) {
            config.setLockWatchdogTimeout(watchdogMillis);   // 默认 30s；崩溃测试缩短 lease
        }
        return org.redisson.Redisson.create(config);
    }

    /** 每实例独立 RedissonClient（生产形态：各 app 实例一个客户端；同客户端同锁对象多线程等待有竞态）。 */
    private OutboxRelay newRelay(long watchdogMillis, MessageBus bus) {
        RedissonClient client = newClient(watchdogMillis);
        clients.add(client);
        MessagingProperties properties = new MessagingProperties("T_", Duration.ofSeconds(30),
            null, null, null, null, null, null);
        return new OutboxRelay(mapper(), tx(), bus, codec,
            new SharedCircuitBreakerGate(null, null, properties) {
                @Override
                public boolean isOpen(String topic) { return false; }
            },
            new BackoffSchedule(null), properties,
            new RedissonLeadership(client, "outbox:relay:leader:test", Duration.ofMillis(200)),
            new OutboxMetrics(new SimpleMeterRegistry()), new SimpleMeterRegistry());
    }

    private final java.util.List<RedissonClient> clients = new java.util.ArrayList<>();

    @Test
    @DisplayName("仅 leader 实例 drain；行投递后删除，无重复投递")
    void onlyLeaderDrains() {
        OutboxRelay leader = newRelay(0, delegate);
        OutboxRelay follower = newRelay(0, delegate);   // 同锁：仅一个能持有
        try {
            leader.start();
            follower.start();

            insertPending("chat_message_save", 0);

            Awaitility.await().atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(leader.isLeader() ^ follower.isLeader())
                    .as("恰好一个 leader").isTrue());
            Awaitility.await().atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(delivered.get()).as("leader 投递").isEqualTo(1));
            Awaitility.await().atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(countRows(null)).isZero());
        } finally {
            leader.stop();
            follower.stop();
        }
    }

    @Test
    @DisplayName("leader 正常 stop() → finally unlock，follower 快速接管（不等看门狗 30s）")
    void followerTakesOverAfterLeaderStop() {
        OutboxRelay leader = newRelay(0, delegate);
        OutboxRelay follower = newRelay(0, delegate);
        try {
            leader.start();
            follower.start();

            Awaitility.await().atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(leader.isLeader() ^ follower.isLeader()).isTrue());

            leader.stop();   // running=false → finally unlock（即时，非 30s）
            long start = System.nanoTime();
            Awaitility.await().atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(follower.isLeader()).as("follower 接管").isTrue());
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start))
                .isLessThan(30);

            // 接管后能投递积压行
            insertPending("chat_message_save", 0);
            Awaitility.await().atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(delivered.get()).isEqualTo(1));
        } finally {
            leader.stop();
            follower.stop();
        }
    }

    @Test
    @DisplayName("leader 崩溃（客户端死亡 → 看门狗停续约）→ lease 到期（缩短为 2s）后 follower 接管")
    void followerTakesOverAfterLeaderCrash() {
        OutboxRelay leader = newRelay(2000, delegate);   // watchdog lease = 2s（缩短真实 30s 等待）
        OutboxRelay follower = newRelay(0, delegate);
        try {
            leader.start();
            follower.start();

            Awaitility.await().atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(leader.isLeader() ^ follower.isLeader()).isTrue());

            // 模拟崩溃：leader 的 Redisson 客户端整体关闭（不 unlock、不 stop）——
            // 看门狗线程随之死亡，锁 lease 到期自动释放（真实 JVM 崩溃的等价路径）
            clients.get(0).shutdown();

            Awaitility.await().atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(follower.isLeader()).as("follower 在 lease 到期后接管").isTrue());

            // 接管后能投递积压行
            insertPending("chat_message_save", 0);
            Awaitility.await().atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(delivered.get()).isEqualTo(1));
        } finally {
            leader.stop();
            follower.stop();
        }
    }
}
