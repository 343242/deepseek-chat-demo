package com.smart.rag.evaluation.testset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GenerationSseBridge} 测试：SseEmitter 的 Handler/initialize 均为包私有，
 * 经反射 + 动态代理安装捕获 Handler，在无 Servlet 容器下断言帧发送与完成/失败语义。
 */
@DisplayName("生成进度 SSE 桥接")
class GenerationSseBridgeTest {

    /** 捕获到的发送/终止语义。 */
    private static final class Capture {
        final List<Object> payloads = new ArrayList<>();
        boolean completed;
        Throwable error;

        @SuppressWarnings("unchecked")
        Object handle(String name, Object[] args) {
            switch (name) {
                case "send" -> {
                    if (args != null && args.length == 2 && args[0] != null
                            && !(args[0] instanceof java.util.Set)) {
                        payloads.add(args[0]);
                    }
                    if (args != null && args.length == 1 && args[0] instanceof java.util.Set<?> set) {
                        set.forEach(item -> payloads.add(dataOf(item)));
                    }
                }
                case "complete" -> completed = true;
                case "completeWithError" -> error = (Throwable) args[0];
                default -> {
                    // onTimeout/onError/onCompletion/flush 无需断言
                }
            }
            return null;
        }

        private static Object dataOf(Object dataWithMediaType) {
            try {
                var method = dataWithMediaType.getClass().getMethod("getData");
                return method.invoke(dataWithMediaType);
            } catch (Exception e) {
                return dataWithMediaType;
            }
        }
    }

    /** 反射安装捕获 Handler，返回捕获器。 */
    private static Capture capture(org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter) {
        var capture = new Capture();
        try {
            Class<?> handlerClass = Class.forName(
                    "org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter$Handler");
            Object handler = Proxy.newProxyInstance(
                    GenerationSseBridgeTest.class.getClassLoader(),
                    new Class<?>[]{handlerClass},
                    (proxy, method, args) -> capture.handle(method.getName(), args));
            Method initialize = ResponseBodyEmitter.class
                    .getDeclaredMethod("initialize", handlerClass);
            initialize.setAccessible(true);
            initialize.invoke(emitter, handler);
        } catch (Exception e) {
            throw new IllegalStateException("安装捕获 Handler 失败", e);
        }
        return capture;
    }

    @Test
    @DisplayName("bridge：sink 事件转发为帧，sink complete 后 emitter 完成")
    void bridgesLiveProgress() {
        var sink = new GenerationProgressSink();
        var bridge = new GenerationSseBridge();
        var jobId = 1L;

        var emitter = bridge.bridge(jobId, sink);
        var capture = capture(emitter);
        assertThat(capture.payloads).isEmpty();

        sink.emit(jobId, new GenerationProgressEvent("kg_build", 3, 10, "主题抽取 3/10"));
        sink.complete(jobId);

        assertThat(capture.payloads).isNotEmpty();
        // 帧内容包含事件对象与事件名（progress）
        assertThat(capture.payloads).anySatisfy(p -> assertThat(p)
                .isInstanceOf(GenerationProgressEvent.class));
        assertThat(capture.payloads).anySatisfy(p -> assertThat(String.valueOf(p))
                .contains("progress"));
        assertThat(capture.completed).isTrue();
        assertThat(capture.error).isNull();
    }

    @Test
    @DisplayName("bridgeTerminated：已完成任务立即回放终态并 complete，不等超时")
    void terminalCompletedReplaysImmediately() {
        var bridge = new GenerationSseBridge();
        var job = new GenerationJobRecord(42L, "ds", 1L, "completed", null, null, 100L,
                null, null, OffsetDateTime.now().minusMinutes(1), OffsetDateTime.now());

        var capture = capture(bridge.bridgeTerminated(job));

        assertThat(capture.payloads).isNotEmpty();
        assertThat(capture.payloads).anySatisfy(p -> assertThat(String.valueOf(p))
                .contains("生成已完成").contains("100"));
        assertThat(capture.completed).isTrue();
        assertThat(capture.error).isNull();
    }

    @Test
    @DisplayName("bridgeTerminated：失败任务回放错误并 completeWithError")
    void terminalFailedCompletesWithError() {
        var bridge = new GenerationSseBridge();
        var job = new GenerationJobRecord(43L, "ds", 1L, "failed", null, null, null,
                "LLM 端点不可用", null, OffsetDateTime.now().minusMinutes(1),
                OffsetDateTime.now());

        var capture = capture(bridge.bridgeTerminated(job));

        assertThat(capture.payloads).isNotEmpty();
        assertThat(capture.payloads).anySatisfy(p -> assertThat(String.valueOf(p))
                .contains("LLM 端点不可用"));
        assertThat(capture.error).isNotNull();
        assertThat(capture.error).hasMessageContaining("LLM 端点不可用");
        assertThat(capture.completed).isFalse();
    }

}
