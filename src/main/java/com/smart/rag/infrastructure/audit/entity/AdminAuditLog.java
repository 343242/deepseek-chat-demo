package com.smart.rag.infrastructure.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("admin_audit_log")
public class AdminAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作者 user id（NULL = 匿名/系统） */
    private Long operatorId;

    private String operatorName;

    private String operatorRole;

    /** mcp_server / mcp_tool / mcp_security / llm_model / rag_pipeline / ... */
    private String resourceType;

    private String resourceId;

    /** create / update / delete / enable / disable / reconnect / refresh_tools / ... */
    private String action;

    /** jsonb，敏感字段已脱敏 */
    private String requestPayload;

    private String resultStatus;
    private String errorCode;
    private String errorMessage;
    private String ipAddress;
    private String userAgent;
    private Integer durationMs;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }

    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }

    public String getOperatorRole() { return operatorRole; }
    public void setOperatorRole(String operatorRole) { this.operatorRole = operatorRole; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getRequestPayload() { return requestPayload; }
    public void setRequestPayload(String requestPayload) { this.requestPayload = requestPayload; }

    public String getResultStatus() { return resultStatus; }
    public void setResultStatus(String resultStatus) { this.resultStatus = resultStatus; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public Integer getDurationMs() { return durationMs; }
    public void setDurationMs(Integer durationMs) { this.durationMs = durationMs; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
