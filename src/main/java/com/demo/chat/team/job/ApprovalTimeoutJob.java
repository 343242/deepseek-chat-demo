package com.demo.chat.team.job;

import com.demo.chat.team.service.TeamApprovalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 审批超时自动拒绝定时任务
 * <p>
 * 每小时检查一次超过 {@link com.demo.chat.team.config.TeamProperties#getApprovalTimeoutDays()} 天未审批的记录。
 */
@Component
public class ApprovalTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(ApprovalTimeoutJob.class);

    private final TeamApprovalService approvalService;

    public ApprovalTimeoutJob(TeamApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @Scheduled(fixedDelay = 3600_000, initialDelay = 600_000)
    public void rejectTimedOutApprovals() {
        try {
            int count = approvalService.rejectTimedOut();
            if (count > 0) {
                log.info("ApprovalTimeoutJob: rejected {} timed-out approvals", count);
            }
        } catch (Exception e) {
            log.error("ApprovalTimeoutJob failed", e);
        }
    }
}
