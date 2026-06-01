package com.smart.rag.infrastructure.fallback;

import com.smart.rag.infrastructure.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StreamRetryHandler")
class StreamRetryHandlerTest {

    @Test
    @DisplayName("retries same model before falling back to next candidate")
    void retriesSameModelBeforeFallback() {
        var handler = new StreamRetryHandler(2, alwaysEligible());
        var firstModelAttempts = new AtomicInteger();
        var secondModelAttempts = new AtomicInteger();

        Flux<String> result = handler.execute(List.of("deepseek/chat", "zhipu/glm"), 0, 0, modelId -> {
            if ("deepseek/chat".equals(modelId)) {
                firstModelAttempts.incrementAndGet();
                return Flux.error(new RuntimeException("timeout"));
            }
            secondModelAttempts.incrementAndGet();
            return Flux.just("ok");
        });

        assertThat(result.collectList().block()).containsExactly("ok");

        assertThat(firstModelAttempts.get()).isEqualTo(2);
        assertThat(secondModelAttempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("skip error falls through to next candidate without retrying same model")
    void skipErrorFallsThroughWithoutRetryingSameModel() {
        var handler = new StreamRetryHandler(3, alwaysEligible());
        var skippedModelAttempts = new AtomicInteger();
        var fallbackAttempts = new AtomicInteger();

        Flux<String> result = handler.execute(List.of("deepseek/chat", "zhipu/glm"), 0, 0, modelId -> {
            if ("deepseek/chat".equals(modelId)) {
                skippedModelAttempts.incrementAndGet();
                return Flux.error(new ModelCircuitOpenException(modelId));
            }
            fallbackAttempts.incrementAndGet();
            return Flux.just("fallback");
        });

        assertThat(result.collectList().block()).containsExactly("fallback");

        assertThat(skippedModelAttempts.get()).isEqualTo(1);
        assertThat(fallbackAttempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("ineligible exception is propagated without retry or fallback")
    void ineligibleExceptionPropagates() {
        var handler = new StreamRetryHandler(3, neverEligible());
        var attempts = new AtomicInteger();
        var error = new BusinessException("内容不允许发送");

        Flux<String> result = handler.execute(List.of("deepseek/chat", "zhipu/glm"), 0, 0, modelId -> {
            attempts.incrementAndGet();
            return Flux.error(error);
        });

        assertThatThrownBy(() -> result.collectList().block())
                .isSameAs(error);

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("post-emission stream failure is propagated without appending fallback output")
    void postEmissionFailurePropagatesWithoutFallbackOutput() {
        var handler = new StreamRetryHandler(2, alwaysEligible());

        Flux<String> result = handler.execute(List.of("deepseek/chat", "zhipu/glm"), 0, 0, modelId -> {
            if ("deepseek/chat".equals(modelId)) {
                return Flux.concat(
                        Flux.just("partial"),
                        Flux.error(new RuntimeException("socket closed")));
            }
            return Flux.just("fallback");
        });

        var chunks = new java.util.ArrayList<String>();
        assertThatThrownBy(() -> result.doOnNext(chunks::add).blockLast())
                .hasMessageContaining("socket closed");

        assertThat(chunks).containsExactly("partial");
    }

    private static FallbackEligibility alwaysEligible() {
        return new FallbackEligibility() {
            @Override
            public boolean isEligible(Throwable e) {
                return true;
            }
        };
    }

    private static FallbackEligibility neverEligible() {
        return new FallbackEligibility() {
            @Override
            public boolean isEligible(Throwable e) {
                return false;
            }
        };
    }
}
