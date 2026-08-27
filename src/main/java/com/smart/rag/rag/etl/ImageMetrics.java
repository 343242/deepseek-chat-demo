package com.smart.rag.rag.etl;

import com.smart.rag.rag.mapper.DocumentImageMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 图片链路指标（design §6.4 M2/中-4 + §6.8）。
 * <ul>
 *   <li>{@code rag.image.pending_total}（Gauge，status tag）——PENDING 积压总量；</li>
 *   <li>{@code rag.image.pending_oldest_seconds}（Gauge）——最老 PENDING 行年龄；</li>
 *   <li>{@code rag.image.consume_seconds}（Timer）——单批消费耗时分布；</li>
 *   <li>{@code rag.image.extract_skipped}（Counter，fail_reason tag）——结构性终态独立计数；</li>
 *   <li>{@code rag.image.extract_stale}（Counter）——超龄 PENDING 告警；</li>
 *   <li>{@code rag.image.alias_occurrence}（Counter，WARN 级观测，v1.5 高-2）；</li>
 *   <li>{@code rag.image.orphan_clean_failed} / {@code manifest_missing} /
 *       {@code version_skew} / {@code placeholder_integrity_degraded}（§6.7/§6.8）。</li>
 * </ul>
 */
@Component
public class ImageMetrics {

    private final DocumentImageMapper documentImageMapper;
    private final MeterRegistry registry;
    private final AtomicLong pendingTotal = new AtomicLong();
    private final AtomicLong pendingOldestSeconds = new AtomicLong();
    private final Timer consumeTimer;
    private final Counter extractStale;
    private final Counter aliasOccurrence;
    private final Counter orphanCleanFailed;
    private final Counter manifestMissing;
    private final Counter versionSkew;
    private final Counter placeholderIntegrityDegraded;

    public ImageMetrics(DocumentImageMapper documentImageMapper, MeterRegistry registry) {
        this.documentImageMapper = documentImageMapper;
        this.registry = registry;
        this.consumeTimer = Timer.builder("rag.image.consume_seconds")
                .description("单批图片消费耗时")
                .register(registry);
        this.extractStale = counter("rag.image.extract_stale", "超龄 PENDING 告警");
        this.aliasOccurrence = counter("rag.image.alias_occurrence", "图片对象别名二次出现观测");
        this.orphanCleanFailed = counter("rag.image.orphan_clean_failed", "图片孤儿清理失败");
        this.manifestMissing = counter("rag.image.manifest_missing", "占位符存在但 manifest 零行");
        this.versionSkew = counter("rag.image.version_skew", "UPLOADED 行 producer_version 漂移");
        this.placeholderIntegrityDegraded =
                counter("rag.image.placeholder_integrity_degraded", "H3 断言降级（高优告警）");
        registry.gauge("rag.image.pending_total", pendingTotal);
        registry.gauge("rag.image.pending_oldest_seconds", pendingOldestSeconds);
    }

    private Counter counter(String name, String description) {
        return Counter.builder(name).description(description).register(registry);
    }

    public Counter skipped(String failReason) {
        return Counter.builder("rag.image.extract_skipped")
                .tag("fail_reason", failReason)
                .register(registry);
    }

    public void consume(Duration duration) {
        consumeTimer.record(duration);
    }

    public void extractStale() { extractStale.increment(); }
    public void aliasOccurrence() { aliasOccurrence.increment(); }
    public void orphanCleanFailed() { orphanCleanFailed.increment(); }
    public void manifestMissing() { manifestMissing.increment(); }
    public void versionSkew() { versionSkew.increment(); }
    public void placeholderIntegrityDegraded() { placeholderIntegrityDegraded.increment(); }

    /** 定时刷新 Gauge（积压可观测，中-4） */
    public void refreshGauges() {
        try {
            long pending = 0;
            for (DocumentImageMapper.StatusCount sc : documentImageMapper.countByStatus()) {
                if ("PENDING".equals(sc.status())) {
                    pending = sc.cnt();
                }
            }
            pendingTotal.set(pending);
            OffsetDateTime oldest = documentImageMapper.oldestPendingCreatedAt();
            pendingOldestSeconds.set(oldest != null
                    ? Duration.between(oldest, OffsetDateTime.now()).toSeconds() : 0);
        } catch (Exception ignored) {
            // 指标刷新失败不影响主流程
        }
    }
}
