package com.smart.rag.infrastructure.concurrent;

import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Reactive adapter that binds a {@link TaskScope} to a {@link Flux} lifecycle via
 * {@link Flux#using}. The scope is opened on subscription and closed on
 * complete/error/cancel.
 *
 * @deprecated This wrapper is fundamentally incompatible with the {@link TaskScope}
 * owner-thread contract. {@code TaskScope} captures {@code Thread.currentThread()}
 * at construction as the owner, and {@code fork}/{@code join}/{@code close} may
 * only be called from that thread. {@link Flux#using} opens the scope on the
 * subscribing thread, but the {@code fluxFactory} produces a {@link Publisher}
 * that frequently emits on a different thread (after {@code subscribeOn} /
 * {@code publishOn} / {@code flatMap} scheduling). Any {@code scope.fork(...)}
 * call from inside the publisher chain will therefore throw
 * {@link ScopeViolationException}.
 * <p>
 * The original motivation was LLM network IO parallelism, but the same goal is
 * now achieved by the synchronous path backed by a {@code ThreadPoolExecutor}
 * (see {@link ExecutorMode#PLATFORM_THREAD_POOL}). The project's main link is
 * Spring MVC + synchronous LLM clients, so this reactive path is no longer needed.
 * <p>
 * Prefer the direct {@code try-with-resources} pattern:
 * <pre>{@code
 * try (TaskScope scope = scopedTasks.open("...")) {
 *     Subtask<A> a = scope.fork("a", this::loadA);
 *     Subtask<B> b = scope.fork("b", this::loadB);
 *     scope.joinUntil(Duration.ofSeconds(3));
 *     scope.throwIfFailed();
 *     return combine(a.result(), b.result());
 * }
 * }</pre>
 * <p>
 * If a true reactive integration is needed in the future, design it so the
 * scope is bound to a {@code reactor.util.context.Context} rather than
 * {@code Thread.currentThread()} — do not use this class.
 */
@Deprecated(since = "0.x", forRemoval = true)
public final class ScopedFlux {

    private ScopedFlux() {
    }

    @Deprecated(since = "0.x", forRemoval = true)
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
