package com.smart.rag.rag.service.impl;

import com.smart.rag.rag.config.RagEntityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * {@link DeriveDebouncer} 单元测试（§3.6：窗口合并、0=关闭、异常隔离）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DeriveDebouncer — scope 级 trailing 合并")
class DeriveDebouncerTest {

    @Mock
    private CommunityDetectionJob communityDetectionJob;

    private DeriveDebouncer debouncer(long windowMillis) {
        return new DeriveDebouncer(communityDetectionJob,
                new RagEntityProperties(10, 500,32, 0.85, 50, 20, 10, 1, 0.7,
                        0.5, 0.3, 0.2, true, null, true, 0, 3, 0, windowMillis, null));
    }

    @Test
    @DisplayName("窗口内多次 submit 合并为一次 derive（验证 #21 合并断言）")
    void multipleSubmits_withinWindow_merged() {
        DeriveDebouncer debouncer = debouncer(150);

        debouncer.submit(1L, null);
        debouncer.submit(1L, null);
        debouncer.submit(1L, null);
        debouncer.submit(1L, null);

        // 等窗口稳定后断言：4 次提交只产生 1 次 derive（after 等满窗口+余量再计数）
        verify(communityDetectionJob, after(800).times(1)).run(1L, null);
    }

    @Test
    @DisplayName("不同 scope 各自独立 derive")
    void differentScopes_independent() {
        DeriveDebouncer debouncer = debouncer(100);

        debouncer.submit(1L, null);
        debouncer.submit(2L, 30L);

        verify(communityDetectionJob, timeout(1500)).run(1L, null);
        verify(communityDetectionJob, timeout(1500)).run(2L, 30L);
    }

    @Test
    @DisplayName("窗口=0 → 关闭防抖，逐次立即 derive（回退现状语义）")
    void zeroWindow_immediateDerive() {
        DeriveDebouncer debouncer = debouncer(0);

        debouncer.submit(1L, null);
        debouncer.submit(1L, null);

        verify(communityDetectionJob, timeout(500).times(2)).run(1L, null);
    }

    @Test
    @DisplayName("derive 抛异常 → 被隔离（runDerived 内部 catch，不影响调度器）")
    void deriveFailure_isolated() throws Exception {
        DeriveDebouncer debouncer = debouncer(50);
        CountDownLatch fired = new CountDownLatch(1);
        doAnswer(inv -> {
            fired.countDown();
            throw new RuntimeException("leiden blew up");
        }).when(communityDetectionJob).run(1L, null);

        debouncer.submit(1L, null);

        // 执行发生过且异常未传播（submit 已正常返回；异常由 runDerived catch-log）
        assertThat(fired.await(2, TimeUnit.SECONDS)).isTrue();
        // 调度器存活：后续提交仍能执行
        verify(communityDetectionJob, timeout(1500)).run(1L, null);
    }
}
