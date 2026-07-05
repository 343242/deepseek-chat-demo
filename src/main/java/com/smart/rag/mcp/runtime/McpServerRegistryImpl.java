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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link McpServerRegistry} + {@link McpServerRegistryAdmin} 双实现。
 * <p>
 * <b>读写分离</b>：读用 volatile {@link AtomicReference} 快照；写经 CAS 切换整个 ImmutableMap。
 * 对齐 {@code LlmClientRegistry.snapshotRef} 模式，避免 v3 缺陷 1.1（{@code ConcurrentHashMap} mutate 期间
 * 请求可能拿到正在异步关闭的旧 client）。
 * <p>
 * <b>初始化挪到 McpAdminService.run()</b>（v4 修复 1.2 打破循环依赖）：本类<b>无</b> {@code @PostConstruct}，
 * 启动后 registry 为空（snapshot=空 ImmutableMap），直到 {@code McpAdminService.run()}（ApplicationRunner）
 * 装配完毕调 {@link #addServer} 填充。
 * <p>
 * <b>占位 server</b>（v4 修复 1.5）：握手失败时 {@code addServer(config, null, errMsg)}，
 * registry 保留带 initError 的 server（不 remove），调用方工具调用返回友好错误。
 * <p>
 * <b>异步关闭旧 client</b>：单线程 + bounded queue + CallerRunsPolicy，
 * 避免旧 client 关闭阻塞 registry mutate。
 */
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

    private final ExecutorService asyncCloseExecutor = new ThreadPoolExecutor(
            1, 1, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            r -> {
                Thread t = new Thread(r, "mcp-async-close");
                t.setDaemon(true);
                return t;
            },
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
                try {
                    impl.closeQuietly();
                } catch (Exception e) {
                    log.debug("MCP server close failed during destroy: {}", e.getMessage());
                }
            }
        });
        asyncCloseExecutor.shutdown();
    }

    private void asyncCloseQuietly(McpServerImpl server) {
        asyncCloseExecutor.submit(() -> {
            try {
                server.closeQuietly();
            } catch (Exception e) {
                log.warn("async close MCP server {} failed: {}", server.id().value(), e.getMessage());
            }
        });
    }
}
