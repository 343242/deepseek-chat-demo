package com.smart.rag.infrastructure.messaging.outbox;

import com.smart.rag.infrastructure.messaging.MessagingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Outbox dead 行清理（design §9）——每天 cron 删除 {@code status='dead'} 且超过
 * {@code dead-retention-days}（默认 7 天）的行，走 {@code idx_outbox_dead_cleanup} 部分索引。
 * <p>
 * {@code dead} 只属于"反复真实投递失败的毒消息"（attempts 在 gate 认为可用时仍反复 send 失败
 * 耗尽 maxAttempts）；MQ 停机期间 attempts 冻结（P1-7）、不转 dead，故 7 天清理不会误删因 MQ
 * 故障积压的行。claiming 超时行由 relay 的 claimPending 查询自动回收，不归本任务。
 */
@Component
public class OutboxCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupScheduler.class);

    private final OutboxMapper mapper;
    private final MessagingProperties properties;

    public OutboxCleanupScheduler(OutboxMapper mapper, MessagingProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    /** cron 外部化（评审"扩展性"硬编码）；dead-retention-days 绑定参数（单源）。 */
    @Scheduled(cron = "${app.messaging.outbox.cleanup-cron:0 0 4 * * *}")
    public void cleanupDead() {
        int deleted;
        try {
            deleted = mapper.deleteDeadBefore(
                Instant.now().minus(java.time.Duration.ofDays(properties.outbox().deadRetentionDays())));
        } catch (Exception e) {
            log.warn("Outbox dead cleanup failed, will retry next day", e);
            return;
        }
        if (deleted > 0) {
            log.info("Outbox dead rows cleaned: count={}, retentionDays={}",
                deleted, properties.outbox().deadRetentionDays());
        }
    }
}
