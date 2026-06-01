package com.smart.rag.team.service;

import com.smart.rag.infrastructure.request.PageRequest;
import com.smart.rag.infrastructure.response.PagedResult;
import com.smart.rag.team.dto.ApprovalReviewRequest;
import com.smart.rag.team.dto.ApprovalVO;
import com.smart.rag.team.dto.MyApprovalVO;

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
}
