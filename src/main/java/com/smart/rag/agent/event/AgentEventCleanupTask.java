package com.smart.rag.agent.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Agent 事件定时清理 -- 清理过期会话事件
 * <p>
 * 每天凌晨 3 点执行，删除超过 {@value #RETENTION_DAYS} 天的事件记录。
 * 依赖 {@code @EnableScheduling}（已在 {@code AdvisorAutoConfiguration} 上启用）。
 */
@Component
public class AgentEventCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(AgentEventCleanupTask.class);

    /** 事件保留天数 */
    private static final int RETENTION_DAYS = 14;

    private final AgentEventMapper eventMapper;

    public AgentEventCleanupTask(AgentEventMapper eventMapper) {
        this.eventMapper = eventMapper;
    }

    /**
     * 定时清理过期事件 -- 每天凌晨 3 点执行
     * <p>
     * 删除 {@code created_at} 早于 {@value #RETENTION_DAYS} 天前的所有事件。
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanup() {
        Instant cutoff = Instant.now().minusSeconds(RETENTION_DAYS * 86400L);
        try {
            int deleted = eventMapper.deleteOlderThan(cutoff);
            if (deleted > 0) {
                log.info("Cleaned up {} agent events older than {} days", deleted, RETENTION_DAYS);
            } else {
                log.debug("No agent events to clean up (cutoff={})", cutoff);
            }
        } catch (Exception e) {
            log.error("Failed to clean up agent events older than {} days", RETENTION_DAYS, e);
        }
    }
}
