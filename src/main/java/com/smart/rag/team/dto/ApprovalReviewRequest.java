package com.smart.rag.team.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ApprovalReviewRequest(
    @NotBlank(message = "审批动作不能为空")
    @Pattern(regexp = "^(APPROVE|REJECT)$", message = "审批动作仅支持 APPROVE 或 REJECT")
    String action,

    @Size(max = 512, message = "审批备注不超过512字符")
    String comment
) {}
