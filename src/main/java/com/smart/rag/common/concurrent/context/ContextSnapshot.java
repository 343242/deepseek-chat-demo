package com.smart.rag.common.concurrent.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ContextSnapshot {

    private static final ContextSnapshot EMPTY = new ContextSnapshot(List.of());

    private final List<CapturedContext<?>> contexts;

    private ContextSnapshot(List<CapturedContext<?>> contexts) {
        this.contexts = contexts;
    }

    public static ContextSnapshot capture(List<ContextCarrier<?>> carriers) {
        if (carriers == null || carriers.isEmpty()) {
            return EMPTY;
        }

        List<CapturedContext<?>> captured = new ArrayList<>(carriers.size());
        for (ContextCarrier<?> carrier : carriers) {
            captured.add(captureOne(carrier));
        }
        return new ContextSnapshot(List.copyOf(captured));
    }

    public ContextRestorer restore() {
        if (contexts.isEmpty()) {
            return () -> {};
        }

        List<RestoredContext<?>> restored = new ArrayList<>(contexts.size());
        try {
            for (CapturedContext<?> context : contexts) {
                restored.add(restoreOne(context));
            }
        } catch (RuntimeException ex) {
            restorePrevious(restored);
            throw ex;
        }

        return () -> restorePrevious(restored);
    }

    private static void restorePrevious(List<RestoredContext<?>> restored) {
        List<RestoredContext<?>> reverse = new ArrayList<>(restored);
        Collections.reverse(reverse);
        RuntimeException failure = null;
        for (RestoredContext<?> context : reverse) {
            try {
                clearOne(context);
            } catch (RuntimeException ex) {
                if (failure == null) {
                    failure = ex;
                } else {
                    failure.addSuppressed(ex);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static <S> CapturedContext<S> captureOne(ContextCarrier<S> carrier) {
        return new CapturedContext<>(carrier, carrier.capture());
    }

    private static <S> RestoredContext<S> restoreOne(CapturedContext<S> context) {
        return new RestoredContext<>(context.carrier(), context.carrier().restore(context.snapshot()));
    }

    private static <S> void clearOne(RestoredContext<S> context) {
        context.carrier().clear(context.previous());
    }

    private record CapturedContext<S>(ContextCarrier<S> carrier, S snapshot) {}

    private record RestoredContext<S>(ContextCarrier<S> carrier, S previous) {}
}
