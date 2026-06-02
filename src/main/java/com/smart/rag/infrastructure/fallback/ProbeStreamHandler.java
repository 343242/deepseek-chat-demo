package com.smart.rag.infrastructure.fallback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 首包探测流包装器
 * <p>
 * 在流式场景下对模型的首包响应设置超时。
 * 若超时前未收到任何数据，则记录熔断失败并抛出 {@link ProbeTimeoutException}，
 * 由 {@link StreamRetryHandler} 触发降级。
 * <p>
 * 非 {@code @Component}，由 {@link FallbackAutoConfiguration} 创建。
 */
public class ProbeStreamHandler {

    private static final Logger log = LoggerFactory.getLogger(ProbeStreamHandler.class);

    private final ChatCandidatesProperties props;
    private final ModelCircuitBreakerRegistry breakers;

    public ProbeStreamHandler(ChatCandidatesProperties props,
                              ModelCircuitBreakerRegistry breakers) {
        this.props = props;
        this.breakers = breakers;
    }

    /**
     * 为流添加首包探测超时
     *
     * @param modelId 模型 ID（用于日志和熔断记录）
     * @param source  原始流
     * @return 包装后的流（探测关闭时原样返回）
     */
    public Flux<String> wrapWithProbe(String modelId, Flux<String> source) {
        if (!props.probeEnabled()) {
            return source;
        }

        Duration timeout = Duration.ofSeconds(props.probeTimeoutSeconds());
        AtomicBoolean gotFirst = new AtomicBoolean(false);

        return Flux.create(sink -> {
            Disposable.Composite disposables = Disposables.composite();

            // Timeout timer
            Disposable timer = Mono.delay(timeout)
                    .subscribe(ignored -> {
                        if (gotFirst.compareAndSet(false, true)) {
                            log.warn("First-packet probe timeout ({}s) for model '{}'",
                                    props.probeTimeoutSeconds(), modelId);
                            breakers.recordFailure(modelId);
                            sink.error(new ProbeTimeoutException(modelId));
                        }
                    });
            disposables.add(timer);

            // Actual stream
            Disposable sub = source.subscribe(
                    item -> {
                        if (gotFirst.compareAndSet(false, true)) {
                            timer.dispose();
                        }
                        sink.next(item);
                    },
                    error -> {
                        timer.dispose();
                        sink.error(error);
                    },
                    () -> {
                        timer.dispose();
                        sink.complete();
                    }
            );
            disposables.add(sub);

            sink.onDispose(disposables);
        });
    }
}
