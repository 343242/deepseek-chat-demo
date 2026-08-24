package com.smart.rag.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.DayOfWeek;

/**
 * 实体中心检索配置（{@code app.rag.entity.*}）。
 * <p>
 * 实体层无条件装配（无总开关）：抽取侧与在线检索侧共用此前缀。
 * <p>
 * ISP：检索配置字段（matchThreshold … communityDetectionEnabled）与抽取配置字段内聚于此类，
 * 消费方通过 record 访问器获取。
 */
@ConfigurationProperties(prefix = "app.rag.entity")
public record RagEntityProperties(
        // === 抽取 ===
        int embeddingBatchSize,
        int descriptionMaxLength,
        // === 在线检索（§7.1）===
        double matchThreshold,
        int frontierBudget,
        int chunkTopK,
        int expandChunkTopK,
        int expansionHops,
        double expansionDecay,
        double alpha,
        double beta,
        double gamma,
        boolean weakTieEnabled,
        String extractionModel,
        boolean communityDetectionEnabled,
        // === V30 共现图增量维护（docs/design/incremental-cooccurrence-maintenance.md）===
        long lockTimeoutMillis,
        int lockRetryAttempts,
        long writeGateWaitMillis,
        long deriveDebounceMillis,
        Reconcile reconcile
) {

    /**
     * 每日对账配置（V30 §6/§7：{@code app.rag.entity.reconcile.*}）。
     */
    public record Reconcile(
            boolean enabled,
            String cron,
            DayOfWeek forceDeriveDay,
            int relinkLimit
    ) {
        public Reconcile {
            if (cron == null || cron.isBlank()) cron = "0 0 8 * * *";
            if (forceDeriveDay == null) forceDeriveDay = DayOfWeek.MONDAY;
            if (relinkLimit < 0) relinkLimit = 0;
        }
    }

    public RagEntityProperties {
        // 抽取默认值
        if (embeddingBatchSize <= 0) embeddingBatchSize = 10;
        if (descriptionMaxLength <= 0) descriptionMaxLength = 500;
        // 检索默认值（§7.1）
        if (matchThreshold <= 0) matchThreshold = 0.85;
        if (frontierBudget <= 0) frontierBudget = 50;
        if (chunkTopK <= 0) chunkTopK = 20;
        if (expandChunkTopK < 0) expandChunkTopK = 10;
        if (expansionHops < 0) expansionHops = 1;
        if (expansionDecay <= 0 || expansionDecay > 1) expansionDecay = 0.7;
        if (alpha < 0 || beta < 0 || gamma < 0)
            throw new IllegalArgumentException("实体检索权重 α/β/γ 必须均 >= 0（alpha=" + alpha
                    + ", beta=" + beta + ", gamma=" + gamma + "）");
        if (alpha + beta + gamma == 0)
            throw new IllegalArgumentException("实体检索权重 α/β/γ 不能同时为 0，请至少配置一个非零权重");
        // V30 增量维护默认值
        if (lockTimeoutMillis <= 0) lockTimeoutMillis = 10_000;      // §8-4 初始值，验证 #12 压测定标
        if (lockRetryAttempts <= 0) lockRetryAttempts = 3;
        if (writeGateWaitMillis <= 0) writeGateWaitMillis = 120_000;  // §3.6 写闸门等待上限（0 无意义）
        if (deriveDebounceMillis < 0) deriveDebounceMillis = 30_000; // §3.6 derive 防抖窗口，0=关闭
        if (reconcile == null) reconcile = new Reconcile(true, "0 0 8 * * *", DayOfWeek.MONDAY, 0);
    }
}
