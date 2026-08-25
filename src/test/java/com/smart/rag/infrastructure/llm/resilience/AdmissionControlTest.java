package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AdmissionControl 并发准入闸门（WS4）")
class AdmissionControlTest {

    private AdmissionControl control(int maxConcurrent, long acquireTimeoutMs) {
        return new AdmissionControl("test-candidate", maxConcurrent, acquireTimeoutMs, null);
    }

    @Test
    @DisplayName("阻塞·并发耗尽 → acquire 超时抛 LLM_BUSY")
    void blockingExhaustionThrowsBusy() {
        AdmissionControl ctl = control(1, 50);
        try (AdmissionControl.Lease l = ctl.acquireBlocking()) {
            assertThatThrownBy(ctl::acquireBlocking)
                .isInstanceOf(RemoteException.class)
                .hasMessageContaining("concurrency limit");
        }
        // 释放后可再获取
        try (AdmissionControl.Lease l2 = ctl.acquireBlocking()) {
            assertThat(l2).isNotNull();
        }
    }

    @Test
    @DisplayName("release 双标志（决策 17）：重复 close 仅首个生效，permit 计数不变")
    void doubleReleaseIsIdempotent() {
        AdmissionControl ctl = control(2, 100);
        AdmissionControl.Lease lease = ctl.acquireBlocking();
        lease.close();
        lease.close(); // 第二次为 no-op

        // 2 个 permit 都应可得
        try (AdmissionControl.Lease a = ctl.acquireBlocking();
             AdmissionControl.Lease b = ctl.acquireBlocking()) {
            assertThat(a).isNotNull();
            assertThat(b).isNotNull();
        }
    }

    @Test
    @DisplayName("acquire 失败路径绝不释放（未获取者无 Lease）")
    void failedAcquireNeverReleases() {
        AdmissionControl ctl = control(1, 30);
        try (AdmissionControl.Lease held = ctl.acquireBlocking()) {
            assertThatThrownBy(ctl::acquireBlocking).isInstanceOf(RemoteException.class);
            // 失败路径之后无额外 release 调用——held 释放后闸门应完全归零可用
        }
        try (AdmissionControl.Lease next = ctl.acquireBlocking()) {
            assertThat(next).isNotNull();
        }
    }

    @Test
    @DisplayName("中断恢复：acquire 中断后标志位还原且无 permit 泄漏")
    void interruptRestoresFlagAndNoLeak() throws Exception {
        AdmissionControl ctl = control(1, 10_000);
        try (AdmissionControl.Lease held = ctl.acquireBlocking()) {
            Thread victim = new Thread(() -> {
                assertThatThrownBy(ctl::acquireBlocking).isInstanceOf(RemoteException.class);
            });
            victim.start();
            victim.interrupt();
            victim.join(2000);
            assertThat(victim.isAlive()).isFalse();
        }
        // 中断路径未获得 permit → 无泄漏，下一次 acquire 成功
        try (AdmissionControl.Lease next = ctl.acquireBlocking()) {
            assertThat(next).isNotNull();
        }
    }

    @Test
    @DisplayName("流式·CANCEL 释放 permit")
    void streamCancelReleasesPermit() {
        AdmissionControl ctl = control(1, 100);

        CountDownLatch subscribed = new CountDownLatch(1);
        Flux<String> stream = ctl.gateStream(() -> Flux.<String>never()
            .doOnSubscribe(s -> subscribed.countDown()));

        reactor.core.Disposable disposable = stream.subscribe();
        assertThat(blockOn(subscribed)).isTrue();
        disposable.dispose();

        // 取消后 permit 归还：可再次获取
        try (AdmissionControl.Lease l = ctl.acquireBlocking()) {
            assertThat(l).isNotNull();
        }
    }

    @Test
    @DisplayName("流式·并发耗尽 → acquire 超时后 Mono.error(LLM_BUSY)")
    void streamExhaustionErrorsWithBusy() {
        AdmissionControl ctl = control(1, 80);
        try (AdmissionControl.Lease held = ctl.acquireBlocking()) {
            java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
            ctl.gateStream(() -> Flux.<String>just("never"))
                .collectList()
                .subscribe(r -> {}, error::set);
            await(() -> error.get() != null, 2000);
            assertThat(error.get()).isInstanceOf(RemoteException.class);
            assertThat(((RemoteException) error.get()).getErrorCode()).isEqualTo(RemoteErrorCode.LLM_BUSY);
        }
        // BUSY 后闸门无泄漏
        try (AdmissionControl.Lease l = ctl.acquireBlocking()) {
            assertThat(l).isNotNull();
        }
    }

    @Test
    @DisplayName("流式·非阻塞轮询（决策 16）：acquire 等待期间不占阻塞线程，permit 归还后自动放行")
    void streamPollingAcquiresWithoutBlocking() throws Exception {
        AdmissionControl ctl = control(1, 5_000);
        AtomicBoolean streamSubscribed = new AtomicBoolean(false);

        try (AdmissionControl.Lease held = ctl.acquireBlocking()) {

            CountDownLatch done = new CountDownLatch(1);
            var disposable = ctl.gateStream(() -> Flux.<String>just("a", "b")
                    .doOnSubscribe(s -> streamSubscribed.set(true)))
                .collectList()
                .subscribe(r -> done.countDown());

            // 等待一拍确认轮询未阻塞（此时 permit 仍被 held 占用）
            Thread.sleep(120);
            assertThat(streamSubscribed.get()).isFalse();

            held.close(); // 归还 permit → 轮询应立刻获得并放行
            assertThat(done.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(streamSubscribed.get()).isTrue();
            disposable.dispose();
        }
    }

    @Test
    @DisplayName("DISABLED 单例直通：不限制并发")
    void disabledPassesThrough() {
        AdmissionControl disabled = AdmissionControl.DISABLED;
        assertThat(disabled.isEnabled()).isFalse();
        for (int i = 0; i < 10; i++) {
            AdmissionControl.Lease lease = disabled.acquireBlocking();
            lease.close();
        }
        assertThat(disabled.gateStream(() -> Flux.just("x")).collectList().block(java.time.Duration.ofSeconds(2)))
            .containsExactly("x");
    }

    private static void await(java.util.function.BooleanSupplier cond, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private static boolean blockOn(CountDownLatch latch) {
        try {
            return latch.await(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
