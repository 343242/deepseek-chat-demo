package com.smart.rag.infrastructure.fallback;

import com.smart.rag.infrastructure.llm.config.ProbeProperties;
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
 * 若超时前未收到任何数据，则记录熔断失败并抛出 {@link ProbeTimeoutException}。
 */
public class ProbeStreamHandler {

    private static final Logger log = LoggerFactory.getLogger(ProbeStreamHandler.class);

    private final ProbeProperties probeProps;
    private final ModelCircuitBreakerRegistry breakers;

    public ProbeStreamHandler(ProbeProperties probeProps,
                              ModelCircuitBreakerRegistry breakers) {
        this.probeProps = probeProps;
        this.breakers = breakers;
    }

    /**
     * 为流添加首包探测超时
     */
    public <T> Flux<T> wrapWithProbe(String modelId, Flux<T> source) {
        if (!probeProps.effectiveEnabled()) {
            return source;
        }

        Duration timeout = Duration.ofMillis(probeProps.effectiveProbeTimeoutMs());
        AtomicBoolean gotFirst = new AtomicBoolean(false);

        return Flux.<T>create(sink -> {
            Disposable.Composite disposables = Disposables.composite();

            Disposable timer = Mono.delay(timeout)
                    .subscribe(ignored -> {
                        if (gotFirst.compareAndSet(false, true)) {
                            log.warn("First-packet probe timeout ({}ms) for model '{}'",
                                    probeProps.effectiveProbeTimeoutMs(), modelId);
                            breakers.recordFailure(modelId);
                            sink.error(new ProbeTimeoutException(modelId));
                        }
                    });
            disposables.add(timer);

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
