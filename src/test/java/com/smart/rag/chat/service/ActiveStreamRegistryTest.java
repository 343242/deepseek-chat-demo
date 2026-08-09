package com.smart.rag.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ActiveStreamRegistry 单元测试（design chat-stream-cancel.md §4.1）。
 * <p>
 * 验证点：
 * <ul>
 *   <li>register/cancel/unregister 基本流程</li>
 *   <li>CAS 替换：register 同键返回旧流并触发其软取消（先置 flag 再 emit）</li>
 *   <li>CAS 注销：旧流不能误删已被替换的新流条目</li>
 *   <li>并发/重复 cancel：第二次 tryEmitEmpty 失败仍返回 true（命中即视为已取消，P2-1）</li>
 *   <li>cancel 不存在的 key 返回 false（幂等）</li>
 * </ul>
 */
@DisplayName("ActiveStreamRegistry 活跃流注册与软取消")
class ActiveStreamRegistryTest {

    private final ActiveStreamRegistry registry = new ActiveStreamRegistry();

    private static ActiveStreamRegistry.ActiveStream newStream() {
        return new ActiveStreamRegistry.ActiveStream(
                Sinks.empty(),
                new AtomicBoolean(false),
                new AtomicReference<>(),
                new AtomicReference<>(new SseEmitter()),
                System.currentTimeMillis(),
                "42"
        );
    }

    @Test
    @DisplayName("register + cancel：cancel 先置 cancelled 标志、再 emit cancelSink")
    void registerAndCancel() {
        ActiveStreamRegistry.ActiveStream s = newStream();
        registry.register("u_1_conv-1", s);

        boolean cancelled = registry.cancel("u_1_conv-1", "USER_ABORT");

        assertThat(cancelled).isTrue();
        assertThat(s.cancelled()).isTrue();
        assertThat(s.cancelReason().get()).isEqualTo("USER_ABORT");
    }

    @Test
    @DisplayName("cancel 不存在的 key 返回 false（幂等）")
    void cancelMissingReturnsFalse() {
        assertThat(registry.cancel("u_1_nope", "USER_ABORT")).isFalse();
    }

    @Test
    @DisplayName("register 同键：返回旧流并触发其软取消（单会话单流替换，design §5.1）")
    void registerReplacesAndSoftCancelsOld() {
        ActiveStreamRegistry.ActiveStream s1 = newStream();
        ActiveStreamRegistry.ActiveStream s2 = newStream();

        ActiveStreamRegistry.ActiveStream old = registry.register("u_1_conv", s1);
        assertThat(old).isNull();
        assertThat(registry.size()).isEqualTo(1);

        old = registry.register("u_1_conv", s2);
        assertThat(old).isSameAs(s1);
        // 旧流被软取消
        assertThat(s1.cancelled()).isTrue();
        // 新流未被取消
        assertThat(s2.cancelled()).isFalse();
        // registry 持有的是新流
        assertThat(registry.getIfPresent("u_1_conv")).isSameAs(s2);
    }

    @Test
    @DisplayName("unregister CAS：旧流不能误删已被替换的新流条目（design §5.3）")
    void unregisterCasDoesNotRemoveNewStream() {
        ActiveStreamRegistry.ActiveStream s1 = newStream();
        ActiveStreamRegistry.ActiveStream s2 = newStream();

        registry.register("u_1_conv", s1);
        registry.register("u_1_conv", s2);  // s1 被替换

        // 旧流 s1 试图注销——不应删掉 s2
        registry.unregister("u_1_conv", s1);
        assertThat(registry.getIfPresent("u_1_conv")).isSameAs(s2);

        // 新流 s2 注销——应成功
        registry.unregister("u_1_conv", s2);
        assertThat(registry.getIfPresent("u_1_conv")).isNull();
    }

    @Test
    @DisplayName("并发/重复 cancel：第二次返回 true（信号已缓存，P2-1）")
    void repeatedCancelReturnsTrue() {
        ActiveStreamRegistry.ActiveStream s = newStream();
        registry.register("u_1_conv", s);

        boolean first = registry.cancel("u_1_conv", "USER_ABORT");
        boolean second = registry.cancel("u_1_conv", "USER_ABORT");

        assertThat(first).isTrue();
        assertThat(second).isTrue();  // 命中 key 即视为已取消，忽略 EmitResult 失败
    }

    @Test
    @DisplayName("unregister 后再 cancel 返回 false")
    void cancelAfterUnregister() {
        ActiveStreamRegistry.ActiveStream s = newStream();
        registry.register("u_1_conv", s);
        registry.unregister("u_1_conv", s);

        assertThat(registry.cancel("u_1_conv", "USER_ABORT")).isFalse();
    }

    @Test
    @DisplayName("跨用户隔离：不同 userId 的 isolatedId 互不影响")
    void crossUserIsolation() {
        ActiveStreamRegistry.ActiveStream s1 = newStream();
        registry.register("u_1_conv", s1);

        // 用户 2 取消用户 1 的流——key 不同，命中失败
        assertThat(registry.cancel("u_2_conv", "USER_ABORT")).isFalse();
        assertThat(s1.cancelled()).isFalse();

        // 用户 2 自己注册
        ActiveStreamRegistry.ActiveStream s2 = newStream();
        registry.register("u_2_conv", s2);
        assertThat(registry.cancel("u_2_conv", "USER_ABORT")).isTrue();
    }
}
