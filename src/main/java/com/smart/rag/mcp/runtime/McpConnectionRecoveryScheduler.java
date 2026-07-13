package com.smart.rag.mcp.runtime;

import com.smart.rag.mcp.admin.entity.McpServerConfig;
import com.smart.rag.mcp.admin.mapper.McpServerConfigMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Bounded scheduler for MCP connection recovery (design §6).
 * <p>
 * 8 workers, queue 200, process-local inFlight set for de-duplication.
 * On saturation, stops the current batch and applies one jittered scan delay.
 */
@Component
public class McpConnectionRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionRecoveryScheduler.class);
    private static final int WORKERS = 8;
    private static final int QUEUE_SIZE = 200;
    private static final int BATCH_LIMIT = 100;

    private final McpServerConfigMapper serverMapper;
    private final McpConnectionReconciler reconciler;
    private final McpResilienceProperties resilienceProperties;

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            WORKERS, WORKERS, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(QUEUE_SIZE),
            Thread.ofPlatform().name("mcp-reconcile-", 0).daemon(true).factory(),
            new ThreadPoolExecutor.AbortPolicy()
    );

    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private volatile long saturatedUntilNanos = 0;

    public McpConnectionRecoveryScheduler(McpServerConfigMapper serverMapper,
                                          McpConnectionReconciler reconciler,
                                          McpResilienceProperties resilienceProperties) {
        this.serverMapper = serverMapper;
        this.reconciler = reconciler;
        this.resilienceProperties = resilienceProperties;
    }

    /**
     * Periodic scan for due reconciliation work.
     * Runs every 10 seconds after a 30-second startup delay.
     */
    @Scheduled(fixedDelay = 10_000, initialDelay = 30_000)
    public void scheduledScan() {
        scan();
    }

    /**
     * Scan for due rows and submit reconciliation work.
     */
    public void scan() {
        if (!resilienceProperties.isRecoveryEnabled()) {
            return;
        }
        if (System.nanoTime() < saturatedUntilNanos) {
            return;
        }

        List<McpServerConfig> due = serverMapper.selectDueForReconcile(BATCH_LIMIT);
        for (McpServerConfig config : due) {
            submit(config.getServerId());
        }
    }

    /**
     * Best-effort wake for a specific server (Admin mutation).
     */
    public void wake(String serverId) {
        submit(serverId);
    }

    private void submit(String serverId) {
        if (!inFlight.add(serverId)) {
            return; // already in flight
        }
        try {
            executor.execute(() -> {
                try {
                    reconciler.reconcile(serverId);
                } catch (RuntimeException e) {
                    log.warn("Reconcile failed for serverId={}: {}", serverId, e.getMessage());
                } finally {
                    inFlight.remove(serverId);
                }
            });
        } catch (RejectedExecutionException rejected) {
            inFlight.remove(serverId);
            saturatedUntilNanos = System.nanoTime() + jitteredDelay();
            log.warn("Reconcile executor saturated, delaying next scan");
        }
    }

    private static long jitteredDelay() {
        long base = 1000L + (long) (Math.random() * 2000L); // 1-3 seconds
        return base * 1_000_000L; // to nanos
    }

    @PreDestroy
    void destroy() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
