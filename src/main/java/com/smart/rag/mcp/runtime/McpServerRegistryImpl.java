package com.smart.rag.mcp.runtime;

import com.google.common.collect.ImmutableMap;
import com.smart.rag.infrastructure.fallback.FallbackEligibility;
import com.smart.rag.mcp.admin.entity.McpServerConfig;
import com.smart.rag.mcp.core.McpServer;
import com.smart.rag.mcp.core.McpServerRegistry;
import com.smart.rag.mcp.core.ServerId;
import com.smart.rag.mcp.mcpclient.SyncMcpToolCallbackProvider;
import com.smart.rag.mcp.policy.McpAuthorizer;
import com.smart.rag.mcp.policy.McpDescriptionSanitizer;
import io.modelcontextprotocol.client.McpSyncClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Atomic snapshot registry with bounded asynchronous close of replaced clients. */
@Component
public class McpServerRegistryImpl implements McpServerRegistry, McpServerRegistryAdmin {

    private static final Logger log = LoggerFactory.getLogger(McpServerRegistryImpl.class);

    private final McpAuthorizer authorizer;
    private final McpCircuitBreakerRegistry circuitRegistry;
    private final FallbackEligibility fallbackEligibility;
    private final McpDescriptionSanitizer descriptionSanitizer;
    private final ObjectProvider<SyncMcpToolCallbackProvider> providerProvider;

    private final AtomicReference<ImmutableMap<ServerId, McpServer>> snapshotRef =
            new AtomicReference<>(ImmutableMap.of());

    private final AtomicLong version = new AtomicLong(0L);

    private static final ThreadFactory CLOSE_THREAD_FACTORY = Thread.ofPlatform()
            .name("mcp-async-close-", 0)
            .daemon(true)
            .factory();

    private final ExecutorService asyncCloseExecutor = new ThreadPoolExecutor(
            1, 1, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            CLOSE_THREAD_FACTORY,
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public McpServerRegistryImpl(McpAuthorizer authorizer,
                                  McpCircuitBreakerRegistry circuitRegistry,
                                  FallbackEligibility fallbackEligibility,
                                  McpDescriptionSanitizer descriptionSanitizer,
                                  ObjectProvider<SyncMcpToolCallbackProvider> providerProvider) {
        this.authorizer = authorizer;
        this.circuitRegistry = circuitRegistry;
        this.fallbackEligibility = fallbackEligibility;
        this.descriptionSanitizer = descriptionSanitizer;
        this.providerProvider = providerProvider;
    }

    // === McpServerRegistry（只读）===

    @Override
    public List<McpServer> list() {
        return List.copyOf(snapshotRef.get().values());
    }

    @Override
    public Optional<McpServer> find(ServerId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(snapshotRef.get().get(id));
    }

    // === McpServerRegistryAdmin（写，原子快照切换）===

    @Override
    public void addServer(McpServerConfig config,
                          @Nullable McpSyncClient client,
                          @Nullable String initError) {
        String sid = config.getServerId();
        if (sid == null || sid.isBlank()) {
            sid = "unreachable-" + (config.getId() != null ? config.getId() : System.nanoTime());
        }
        ServerId id = new ServerId(sid);
        McpServerImpl server = new McpServerImpl(id, client, authorizer, circuitRegistry,
                fallbackEligibility, providerProvider.getIfAvailable(), initError, descriptionSanitizer);

        ImmutableMap<ServerId, McpServer> oldSnapshot;
        ImmutableMap<ServerId, McpServer> newSnapshot;
        do {
            oldSnapshot = snapshotRef.get();
            ImmutableMap.Builder<ServerId, McpServer> b = ImmutableMap.builder();
            oldSnapshot.forEach((k, v) -> {
                if (!k.equals(id)) {
                    b.put(k, v);
                }
            });
            b.put(id, server);
            newSnapshot = b.build();
        } while (!snapshotRef.compareAndSet(oldSnapshot, newSnapshot));

        McpServer previous = oldSnapshot.get(id);
        if (previous instanceof McpServerImpl oldImpl && oldImpl.hasClient()) {
            asyncCloseQuietly(oldImpl);
        }
        version.incrementAndGet();
        log.info("MCP server registered: id={} client={} initError={}",
                id.value(), client != null ? "present" : "null",
                initError != null ? "present" : "null");
    }

    @Override
    public void removeServer(ServerId id) {
        ImmutableMap<ServerId, McpServer> oldSnapshot;
        ImmutableMap<ServerId, McpServer> newSnapshot;
        do {
            oldSnapshot = snapshotRef.get();
            if (!oldSnapshot.containsKey(id)) {
                return;
            }
            ImmutableMap.Builder<ServerId, McpServer> b = ImmutableMap.builder();
            oldSnapshot.forEach((k, v) -> {
                if (!k.equals(id)) {
                    b.put(k, v);
                }
            });
            newSnapshot = b.build();
        } while (!snapshotRef.compareAndSet(oldSnapshot, newSnapshot));

        McpServer removed = oldSnapshot.get(id);
        if (removed instanceof McpServerImpl oldImpl && oldImpl.hasClient()) {
            asyncCloseQuietly(oldImpl);
        }
        circuitRegistry.evict(id.value());
        version.incrementAndGet();
        log.info("MCP server removed: id={}", id.value());
    }

    @Override
    public void replaceServer(McpServerConfig config, McpSyncClient newClient) {
        addServer(config, newClient, null);
    }

    @Override
    public long currentVersion() {
        return version.get();
    }

    @PreDestroy
    void destroy() {
        ImmutableMap<ServerId, McpServer> snapshot = snapshotRef.getAndSet(ImmutableMap.of());
        snapshot.values().forEach(s -> {
            if (s instanceof McpServerImpl impl && impl.hasClient()) {
                impl.closeQuietly();
            }
        });
        asyncCloseExecutor.shutdown();
        try {
            if (!asyncCloseExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                asyncCloseExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncCloseExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void asyncCloseQuietly(McpServerImpl server) {
        asyncCloseExecutor.submit(server::closeQuietly);
    }
}
