package com.smart.rag.infrastructure.llm.registry;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.smart.rag.infrastructure.concurrent.ScopeOptions;
import com.smart.rag.infrastructure.concurrent.ScopePolicy;
import com.smart.rag.infrastructure.concurrent.ScopeTimeoutException;
import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.infrastructure.concurrent.TaskScope;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.config.LlmByokProperties;
import com.smart.rag.infrastructure.llm.config.LlmConfigSource;
import com.smart.rag.infrastructure.llm.metrics.LlmMetrics;
import com.smart.rag.infrastructure.llm.resilience.LlmCircuitBreakerAdapterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LLM 客户端注册表 — 无锁读写分离 + BYOK per-user 快照（design §5.3）。
 * <p>
 * <b>系统级</b>：{@link AtomicReference}&lt;{@link RegistrySnapshot}&gt;，yml 全员共享底座；
 * 旧 API（{@code get/getDefault/getChain/...}）零改动，14 调用方不受影响。
 * <p>
 * <b>用户级（BYOK，本期 CHAT-only）</b>：{@link #userSnapshots}（Caffeine 有界）cache-aside，
 * {@code getUserChain/getUserDefault} cache miss 时 lazy 从 {@link LlmConfigSource} 构建；
 * 空链（DB 无行/全 disabled）→ delegate 系统级 snapshot（<b>不缓存</b>，P0-1 天然满足）。
 * {@code app.llm.byok.enabled=false} 时 getUser* 直接走系统级（N4 回滚）。
 * <p>
 * <b>资源管理</b>：invalidateUser/淘汰 → 异步 close 旧 client（专用小池，<b>不复用 fork-join</b>）
 * + {@code circuitBreakerRegistry.evict}（P1-6 防熔断器泄漏）；@PreDestroy 排空。
 */
@Component
public class LlmClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(LlmClientRegistry.class);

    private static final Duration DEFAULT_DESTROY_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_DESTROY_CONCURRENCY = 8;
    private static final Duration USER_SNAPSHOT_TTL = Duration.ofHours(1);
    private static final int ASYNC_CLOSE_CORE = 2;
    private static final int ASYNC_CLOSE_MAX = 4;
    private static final int ASYNC_CLOSE_QUEUE = 100;
    private static final long ASYNC_CLOSE_AWAIT_SECONDS = 30L;

    private final LlmClientFactory factory;
    private final ScopedTasks scopedTasks;
    private final Duration destroyTimeout;
    private final int destroyConcurrency;
    private final AtomicReference<RegistrySnapshot> snapshotRef;

    private final LlmConfigSource configSource;
    private final LlmByokProperties byokProperties;
    private final LlmCircuitBreakerAdapterRegistry circuitBreakerRegistry;
    @Nullable
    private final LlmMetrics metrics;

    /** per-user BYOK 快照（CHAT-only）；@PostConstruct 初始化，null 表示未 init（getUser* delegate 系统级） */
    @Nullable
    private Cache<Long, RegistrySnapshot> userSnapshots;

    /** 异步 close 旧 client 专用小池（fire-and-forget，不复用 infrastructure.concurrent fork-join） */
    @Nullable
    private ExecutorService asyncCloseExecutor;

    @Autowired
    public LlmClientRegistry(LlmClientFactory factory, ScopedTasks scopedTasks,
                             LlmConfigSource configSource, LlmByokProperties byokProperties,
                             LlmCircuitBreakerAdapterRegistry circuitBreakerRegistry,
                             @Nullable LlmMetrics metrics) {
        this.factory = factory;
        this.scopedTasks = scopedTasks;
        this.destroyTimeout = DEFAULT_DESTROY_TIMEOUT;
        this.destroyConcurrency = DEFAULT_DESTROY_CONCURRENCY;
        this.snapshotRef = new AtomicReference<>(RegistrySnapshot.empty());
        this.configSource = configSource;
        this.byokProperties = byokProperties;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.metrics = metrics;
    }

    // ======================== Lifecycle ========================

    @PostConstruct
    public void init() {
        refresh();
        int size = snapshotRef.get().size();
        if (size == 0) {
            log.warn("LlmClientRegistry initialized with 0 clients — check app.llm configuration");
        } else {
            log.info("LlmClientRegistry initialized: {} clients registered", size);
        }

        int cacheSize = byokProperties.getUserCacheSize() != null ? byokProperties.getUserCacheSize() : 1000;
        this.userSnapshots = Caffeine.newBuilder()
            .maximumSize(cacheSize)
            .expireAfterAccess(USER_SNAPSHOT_TTL)
            .removalListener((Long userId, RegistrySnapshot snap, RemovalCause cause) -> asyncClose(snap))
            .build();

        this.asyncCloseExecutor = new ThreadPoolExecutor(
            ASYNC_CLOSE_CORE, ASYNC_CLOSE_MAX, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(ASYNC_CLOSE_QUEUE),
            r -> {
                Thread t = new Thread(r, "llm-byok-async-close");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());
        log.info("BYOK per-user snapshot cache initialized (maxSize={}, ttl={})", cacheSize, USER_SNAPSHOT_TTL);
    }

    @PreDestroy
    public void destroy() {
        RegistrySnapshot snapshot = snapshotRef.getAndSet(RegistrySnapshot.empty());

        ScopeOptions options = ScopeOptions.builder("llm-registry-destroy")
            .policy(ScopePolicy.COLLECT_ALL)
            .maxConcurrency(destroyConcurrency)
            .build();

        int systemTotal = snapshot.clientsById().size();
        if (systemTotal > 0) {
            try (TaskScope scope = scopedTasks.open("llm-registry-destroy", options)) {
                snapshot.clientsById().forEach((id, client) -> {
                    scope.fork("close-" + id, () -> {
                        try { client.close(); } catch (Exception e) { log.warn("Failed to close client {}: {}", id, e.getMessage()); }
                        return null;
                    });
                });
                try {
                    scope.joinUntil(destroyTimeout);
                } catch (ScopeTimeoutException e) {
                    log.warn("LlmClientRegistry destroy timed out after {} — system clients may still be closing", destroyTimeout);
                }
            }
        }

        // BYOK per-user 快照排空（同步 close + evict 熔断器）
        if (userSnapshots != null) {
            userSnapshots.asMap().values().forEach(snap ->
                snap.clientsById().forEach((id, client) -> {
                    try { client.close(); } catch (Exception e) { log.warn("close user client {} failed: {}", id, e.getMessage()); }
                    evictCircuitBreakerQuietly(id);
                }));
            userSnapshots.invalidateAll();
            userSnapshots.cleanUp();
        }

        if (asyncCloseExecutor != null) {
            asyncCloseExecutor.shutdown();
            try {
                if (!asyncCloseExecutor.awaitTermination(ASYNC_CLOSE_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("BYOK async close executor did not terminate in {}s, forcing shutdown", ASYNC_CLOSE_AWAIT_SECONDS);
                    asyncCloseExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                asyncCloseExecutor.shutdownNow();
            }
        }
        log.info("LlmClientRegistry destroyed ({} system clients)", systemTotal);
    }

    /** 重新构建系统级快照（配置变更时调用） */
    public void refresh() {
        Set<String> preservedDisabled = snapshotRef.get().disabledSet();
        RegistrySnapshot fresh = factory.buildSnapshot();
        RegistrySnapshot newSnapshot = fresh.withDisabledSet(preservedDisabled);
        RegistrySnapshot old = snapshotRef.getAndSet(newSnapshot);
        if (old != null) {
            old.clientsById().forEach((id, client) -> {
                if (!newSnapshot.clientsById().containsKey(id)) {
                    try { client.close(); } catch (Exception e) {
                        log.warn("Failed to close old client {}: {}", id, e.getMessage());
                    }
                }
            });
        }
        log.info("Registry refreshed: {} clients ({} disabled preserved)",
            newSnapshot.size(), preservedDisabled.size());
    }

    // ======================== Query API（系统级，零改动）========================

    public CapabilityClient get(String candidateId) {
        CapabilityClient client = snapshotRef.get().getClient(candidateId);
        if (client == null) {
            throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR,
                "No client registered for candidate: " + candidateId);
        }
        return client;
    }

    public CapabilityClient find(String candidateId) {
        return snapshotRef.get().getClient(candidateId);
    }

    public <T extends CapabilityClient> T get(String candidateId, Class<T> type) {
        CapabilityClient client = get(candidateId);
        if (!type.isInstance(client)) {
            throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR,
                "Client '" + candidateId + "' does not implement " + type.getSimpleName());
        }
        return type.cast(client);
    }

    public CapabilityClient getDefault(LlmCapability capability) {
        CapabilityClient client = snapshotRef.get().getDefaultClient(capability);
        if (client == null) {
            throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR,
                "No default client for capability: " + capability);
        }
        return client;
    }

    public <T extends CapabilityClient> T getDefault(LlmCapability capability, Class<T> type) {
        CapabilityClient client = getDefault(capability);
        if (!type.isInstance(client)) {
            throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR,
                "Default client for " + capability + " does not implement " + type.getSimpleName());
        }
        return type.cast(client);
    }

    public CapabilityClient getDeepThinking(LlmCapability capability) {
        CapabilityClient client = snapshotRef.get().getDeepThinkingClient(capability);
        if (client == null) {
            throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR,
                "No deep-thinking client for capability: " + capability);
        }
        return client;
    }

    public List<CapabilityClient> getChain(LlmCapability capability) {
        return snapshotRef.get().getChain(capability);
    }

    public Set<String> registeredCandidateIds() {
        return snapshotRef.get().clientsById().keySet();
    }

    // ======================== Query API（BYOK per-user，design §5.3）========================

    /**
     * 用户级默认客户端（CHAT-only，本期）：
     * BYOK 链非空 → priority 首位；空链（无行/全 disabled）或 BYOK 关 → delegate 系统级 {@link #getDefault}。
     */
    public CapabilityClient getUserDefault(LlmCapability capability, @Nullable Long userId) {
        if (!supportsByok(capability, userId)) {
            return getDefault(capability);
        }
        List<CapabilityClient> chain = getUserChainInternal(capability, userId);
        if (!chain.isEmpty()) {
            return chain.get(0);
        }
        return getDefault(capability);
    }

    /** 类型安全版本 */
    public <T extends CapabilityClient> T getUserDefault(LlmCapability capability, @Nullable Long userId, Class<T> type) {
        CapabilityClient client = getUserDefault(capability, userId);
        if (!type.isInstance(client)) {
            throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR,
                "Default client for " + capability + " does not implement " + type.getSimpleName());
        }
        return type.cast(client);
    }

    /**
     * 用户级 Fallback Chain：
     * BYOK 链 → 应用系统级 disabledSet 归一化过滤（P1-5：剥 {@code u:{userId}:} 前缀按 modelCode 匹配）；
     * 无 BYOK → delegate 系统级 {@link #getChain}。
     */
    public List<CapabilityClient> getUserChain(LlmCapability capability, @Nullable Long userId) {
        if (!supportsByok(capability, userId)) {
            return getChain(capability);
        }
        return getUserChainInternal(capability, userId);
    }

    private List<CapabilityClient> getUserChainInternal(LlmCapability cap, Long userId) {
        Cache<Long, RegistrySnapshot> cache = userSnapshots;
        if (cache == null) {
            return getChain(cap);
        }
        // cache miss lazy 构建；buildUserSnapshot 返回 null → Caffeine 不缓存（空链 delegate 不缓存）
        RegistrySnapshot userSnap = cache.get(userId, this::buildUserSnapshot);
        if (userSnap == null) {
            return getChain(cap);
        }
        Set<String> systemDisabled = snapshotRef.get().disabledSet();
        if (systemDisabled.isEmpty()) {
            return userSnap.getChain(cap);
        }
        return userSnap.getChain(cap).stream()
            .filter(c -> !systemDisabled.contains(stripUserPrefix(c.candidateId())))
            .toList();
    }

    /** cache miss 时从 LlmConfigSource 构建（含解密 key + 命名空间 candidateId） */
    private RegistrySnapshot buildUserSnapshot(Long userId) {
        List<LlmClientFactory.ResolvedCandidate> resolved =
            configSource.userChain(userId, LlmCapability.CHAT);
        if (resolved.isEmpty()) {
            return null;
        }
        return factory.buildSnapshot(resolved);
    }

    // ======================== BYOK Runtime Control ========================

    /**
     * 失效用户快照（配置变更后清旧，下次请求 cache miss → lazy 重建，design §6）。
     * <p>
     * 触发 removalListener → 异步 close 旧 client + evict 熔断器（P1-6）。
     */
    public void invalidateUser(Long userId) {
        Cache<Long, RegistrySnapshot> cache = userSnapshots;
        if (cache == null || userId == null) {
            return;
        }
        cache.invalidate(userId);
        log.debug("Invalidated BYOK snapshot for user {}", userId);
    }

    // ======================== 系统级 Runtime Control（零改动）========================

    public void disable(String candidateId) {
        snapshotRef.updateAndGet(current -> {
            Set<String> newDisabled = new java.util.LinkedHashSet<>(current.disabledSet());
            if (newDisabled.add(candidateId)) {
                log.info("Disabled candidate: {}", candidateId);
            }
            return current.withDisabledSet(Set.copyOf(newDisabled));
        });
    }

    public void enable(String candidateId) {
        snapshotRef.updateAndGet(current -> {
            Set<String> newDisabled = new java.util.LinkedHashSet<>(current.disabledSet());
            if (newDisabled.remove(candidateId)) {
                log.info("Enabled candidate: {}", candidateId);
            }
            return current.withDisabledSet(Set.copyOf(newDisabled));
        });
    }

    public RegistrySnapshot snapshot() {
        return snapshotRef.get();
    }

    // ======================== BYOK 内部工具 ========================

    /** BYOK 是否对该 (cap, userId) 生效：CHAT-only + enabled + 有 userId + 已 init */
    private boolean supportsByok(LlmCapability cap, @Nullable Long userId) {
        return userId != null
            && cap == LlmCapability.CHAT
            && byokProperties.isEnabled()
            && userSnapshots != null;
    }

    /**
     * 异步 close 旧 snapshot 的所有 client（invalidate/淘汰触发，design §5.3 / R3）。
     * close 异常 → counter + WARN（不抛）；同步 evict 熔断器（P1-6 防泄漏）。
     */
    private void asyncClose(@Nullable RegistrySnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        ExecutorService executor = asyncCloseExecutor;
        if (executor == null) {
            return;
        }
        snapshot.clientsById().forEach((candidateId, client) -> executor.submit(() -> {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("BYOK async close failed for {}: {}", candidateId, e.getMessage());
                if (metrics != null) {
                    metrics.recordByokCloseError();
                }
            }
            evictCircuitBreakerQuietly(candidateId);
        }));
    }

    private void evictCircuitBreakerQuietly(String candidateId) {
        try {
            circuitBreakerRegistry.evict(candidateId);
        } catch (Exception e) {
            log.warn("evict circuit breaker for {} failed: {}", candidateId, e.getMessage());
        }
    }

    /** 剥 BYOK candidateId 命名空间前缀：{@code u:{userId}:{modelCode}} → {@code {modelCode}}（系统级归一化匹配，P1-5） */
    static String stripUserPrefix(String candidateId) {
        if (!candidateId.startsWith("u:")) {
            return candidateId;
        }
        int first = candidateId.indexOf(':');
        int second = candidateId.indexOf(':', first + 1);
        return second >= 0 ? candidateId.substring(second + 1) : candidateId;
    }
}
