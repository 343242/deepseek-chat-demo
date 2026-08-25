package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.llm.config.ConcurrencyConfig;
import com.smart.rag.infrastructure.llm.metrics.LlmMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 并发准入闸门注册表（design llm-resilience-optimization WS4，v1.1 H2）。
 * <p>
 * {@link AdmissionControl} 按 candidateId 全局唯一：
 * <ul>
 *   <li>{@link #getOrCreate}：不存在 → 创建（注册 inflight gauge）；
 *       存在且 <b>整个 ConcurrencyConfig 全等</b>（决策 18：maxConcurrent 或
 *       acquireTimeoutMs 任一变化即替换）→ 复用同一实例——{@code LlmClientRegistry.refresh()}
 *       重建客户端后仍指向同一闸门，无新旧双 semaphore 超发窗口；
 *       config 变化 → 替换新实例（旧 gauge 移除、新 gauge 注册）</li>
 *   <li>{@link #evict}：移除注册表条目 + inflight gauge（杜绝僵尸序列，AC10）。
 *       挂接 {@code LlmClientRegistry.refresh()} 移除候选路径与 {@code destroy()}（决策 19）</li>
 * </ul>
 * 残余窗口（文档化）：仅 config 变更替换瞬间——旧在飞请求持旧闸门引用继续放行至 drain 完成。
 */
@Component
public class AdmissionControlRegistry {

    private static final Logger log = LoggerFactory.getLogger(AdmissionControlRegistry.class);

    private final ConcurrentHashMap<String, AdmissionControl> controls = new ConcurrentHashMap<>();

    @Nullable
    private final LlmMetrics metrics;

    public AdmissionControlRegistry(@Nullable LlmMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * 获取或创建闸门。maxConcurrent <= 0 时返回禁用态（回滚开关）。
     */
    public AdmissionControl getOrCreate(String candidateId, ConcurrencyConfig config) {
        int maxConcurrent = config.effectiveMaxConcurrent();
        if (maxConcurrent <= 0) {
            return AdmissionControl.DISABLED;
        }
        AdmissionControl created = new AdmissionControl(candidateId,
            maxConcurrent, config.effectiveAcquireTimeoutMs(), metrics);
        // compute 保证原子性：存在且 config 全等 → 复用；否则替换（旧 gauge 随 evict 移除）
        AdmissionControl existing = controls.merge(candidateId, created, (oldCtl, newCtl) ->
                sameConfig(oldCtl, newCtl) ? oldCtl : replaceQuietly(candidateId, oldCtl, newCtl));
        return existing;
    }

    private boolean sameConfig(AdmissionControl a, AdmissionControl b) {
        // 有效配置一致即视为同一闸门语义（实例字段仅 candidateId/permits 数量/超时/metrics）
        return a.maxConcurrentForCompare() == b.maxConcurrentForCompare()
            && a.acquireTimeoutMsForCompare() == b.acquireTimeoutMsForCompare();
    }

    private AdmissionControl replaceQuietly(String candidateId, AdmissionControl oldCtl, AdmissionControl newCtl) {
        log.info("Concurrency config changed for candidate '{}', replacing admission control", candidateId);
        evict(candidateId);
        return newCtl;
    }

    /** 移除闸门与 inflight gauge（refresh 移除候选 / destroy 时调用） */
    public void evict(String candidateId) {
        AdmissionControl removed = controls.remove(candidateId);
        if (removed != null) {
            removed.evictGauge();
            log.debug("Evicted admission control for candidate '{}'", candidateId);
        }
    }

    /** 静默 evict（生命周期旁路调用，异常不传播） */
    public void evictQuietly(String candidateId) {
        try {
            evict(candidateId);
        } catch (Exception e) {
            log.warn("Failed to evict admission control for candidate '{}': {}", candidateId, e.getMessage());
        }
    }

    public boolean contains(String candidateId) {
        return controls.containsKey(candidateId);
    }
}
