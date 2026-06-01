package com.smart.rag.infrastructure.concurrent;

import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class ScopedFlux {

    private ScopedFlux() {
    }

    public static <T> Flux<T> using(
            Supplier<? extends TaskScope> scopeFactory,
            Function<? super TaskScope, ? extends Publisher<? extends T>> fluxFactory,
            Consumer<? super TaskScope> beforeClose
    ) {
        return Flux.using(
                scopeFactory::get,
                fluxFactory,
                scope -> {
                    try {
                        beforeClose.accept(scope);
                    } finally {
                        scope.close();
                    }
                },
                true
        );
    }
}
