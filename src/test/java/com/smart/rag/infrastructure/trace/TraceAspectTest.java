package com.smart.rag.infrastructure.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * TraceAspect 单测 — 覆盖 sessionId/userId 提取的优先级与 MDC 兜底。
 * <p>
 * 回归背景：Chat 路径的 {@code ChatReferenceCollector.collect(List<Document>)} 参数无
 * ToolWorkspace / StrategyExecutionContext，provider 无法提取 userId，而 trace_event.user_id
 * 为 NOT NULL —— 修复后由 MDC {@code ragUserId} 兜底，仍缺失时用 0 哨兵。
 */
class TraceAspectTest {

    private final TraceRecorder recorder = mock(TraceRecorder.class);

    /** 被代理的埋点目标：模拟无上下文参数的 Chat 路径方法（如 ChatReferenceCollector.collect） */
    static class Target {
        @TracedStep("CONTEXT_ASSEMBLY")
        public String collect(List<?> docs) {
            return "{\"summary\":\"ok\",\"documentCount\":1}";
        }
    }

    /** 模拟携带上下文的参数（如 StrategyExecutionContext / ToolWorkspace 的替身） */
    record SessionCarrier(String sessionId, Long userId) {}

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private Target newProxiedTarget(TraceContextProvider... providers) {
        AspectJProxyFactory factory = new AspectJProxyFactory(new Target());
        factory.addAspect(new TraceAspect(recorder, new ObjectMapper(), List.of(providers)));
        return factory.getProxy();
    }

    private Long recordedUserId() {
        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(recorder, times(1)).record(any(), anyString(), captor.capture(),
                anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
        return captor.getValue();
    }

    @Test
    @DisplayName("无上下文参数时：userId/sessionId/mode 从 MDC 兜底（Chat 路径入口注入）")
    void mdcFallback_extractsUserIdAndSession() {
        MDC.put("ragSessionId", "s-1");
        MDC.put("ragMode", "CHAT");
        MDC.put("ragUserId", "42");

        newProxiedTarget().collect(List.of("doc"));

        assertThat(recordedUserId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("MDC 也没有 userId 时：兜底 0 哨兵（NOT NULL 约束），不再插入失败")
    void userIdMissing_fallsBackToZeroSentinel() {
        // 不设置任何 MDC（模拟入口未注入的极端情况）
        newProxiedTarget().collect(List.of("doc"));

        assertThat(recordedUserId()).isEqualTo(0L);
    }

    @Test
    @DisplayName("provider 命中时优先于 MDC（userId 不被 MDC 覆盖）")
    void providerTakesPrecedenceOverMdc() {
        MDC.put("ragSessionId", "mdc-session");
        MDC.put("ragUserId", "99");

        TraceContextProvider provider = new TraceContextProvider() {
            @Override
            public boolean supports(Object arg) {
                return arg instanceof SessionCarrier;
            }

            @Override
            public String extractSessionId(Object arg) {
                return ((SessionCarrier) arg).sessionId();
            }

            @Override
            public Long extractUserId(Object arg) {
                return ((SessionCarrier) arg).userId();
            }

            @Override
            public String mode() {
                return MODE_AGENT;
            }
        };

        // 用携带上下文的参数触发 provider 路径
        AspectJProxyFactory factory = new AspectJProxyFactory(new CarrierTarget());
        factory.addAspect(new TraceAspect(recorder, new ObjectMapper(), List.of(provider)));
        CarrierTarget proxy = factory.getProxy();
        proxy.run(new SessionCarrier("agent-session", 7L));

        Long userId = recordedUserId();
        assertThat(userId).isEqualTo(7L);
    }

    /** 携带上下文参数的埋点目标（模拟 Agent 路径 Tool 的 execute(query, workspace)） */
    static class CarrierTarget {
        @TracedStep("VECTOR_SEARCH")
        public String run(SessionCarrier carrier) {
            return "{\"summary\":\"ok\"}";
        }
    }
}
