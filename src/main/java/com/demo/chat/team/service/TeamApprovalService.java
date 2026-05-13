package com.demo.chat.team.service;

import com.demo.chat.common.request.PageRequest;
import com.demo.chat.common.response.PagedResult;
import com.demo.chat.team.dto.ApprovalReviewRequest;
import com.demo.chat.team.dto.ApprovalVO;
import com.demo.chat.team.dto.MyApprovalVO;

/**
 * 团队上传审批服务接口
 */
public interface TeamApprovalService {

    /**
     * 获取待审批列表（管理员/创建者）
     */
    PagedResult<ApprovalVO> listPending(Long teamId, PageRequest req);

    /**
     * 审批操作（管理员/创建者）
     */
    void review(Long teamId, Long approvalId, ApprovalReviewRequest request);

    /**
     * 获取我的审批状态（上传者查看）
     */
    PagedResult<MyApprovalVO> listMyApprovals(Long teamId, PageRequest req);

    /**
     * 超时自动拒绝（定时任务调用）
     */
    int rejectTimedOut();

    /**
     * 审批通过后触发 ETL（内部方法）
     */
    void approveAndTriggerEtl(Long approvalId);
}
