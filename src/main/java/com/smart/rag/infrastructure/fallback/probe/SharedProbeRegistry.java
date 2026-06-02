package com.smart.rag.infrastructure.fallback.probe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 共享探测去重注册表
 * <p>
 * 同一 modelId 的并发探测共享同一个 {@link CompletableFuture}，
 * 避免重复探测。探测完成后自动清理。
 */
public class SharedProbeRegistry {

    private static final Logger log = LoggerFactory.getLogger(SharedProbeRegistry.class);

    private final ConcurrentHashMap<String, CompletableFuture<ProbeResult>> inflight = new ConcurrentHashMap<>();

    /**
     * 尝试注册新探测。若已有同 modelId 的探测在飞则返回 null。
     * 调用方负责在探测完成时调用 {@link #complete} 或 {@link #fail}。
     */
    public CompletableFuture<ProbeResult> tryRegister(String modelId) {
        CompletableFuture<ProbeResult> future = new CompletableFuture<>();
        CompletableFuture<ProbeResult> existing = inflight.putIfAbsent(modelId, future);
        if (existing != null) {
            return null;
        }
        future.whenComplete((r, e) -> {
            inflight.remove(modelId, future);
            log.debug("Shared probe for '{}' completed, registry size={}", modelId, inflight.size());
        });
        return future;
    }

    /**
     * 获取在飞的探测 Future（不注册新的）
     */
    public CompletableFuture<ProbeResult> getInFlight(String modelId) {
        return inflight.get(modelId);
    }

    /**
     * 完成探测（成功）
     */
    public void complete(String modelId, ProbeResult result) {
        CompletableFuture<ProbeResult> future = inflight.remove(modelId);
        if (future != null) {
            future.complete(result);
        }
    }

    /**
     * 完成探测（异常）
     */
    public void fail(String modelId, Throwable error) {
        CompletableFuture<ProbeResult> future = inflight.remove(modelId);
        if (future != null) {
            future.completeExceptionally(error);
        }
    }

    public int size() {
        return inflight.size();
    }
}
