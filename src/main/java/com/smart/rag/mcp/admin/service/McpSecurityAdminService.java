package com.smart.rag.mcp.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.audit.AdminAudit;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.mcp.admin.entity.McpSecurityConfigView;
import com.smart.rag.mcp.admin.mapper.McpSecurityConfigMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;


@Service
public class McpSecurityAdminService {

    private final McpSecurityConfigMapper securityConfigMapper;
    private final TransactionTemplate txTemplate;
    private final McpSecurityConfigAccessor securityConfigAccessor;
    private final ObjectMapper objectMapper;
    private final McpSecurityConfigValidator validator;

    public McpSecurityAdminService(McpSecurityConfigMapper securityConfigMapper,
                                   TransactionTemplate txTemplate,
                                   McpSecurityConfigAccessor securityConfigAccessor,
                                   ObjectMapper objectMapper,
                                   McpSecurityConfigValidator validator) {
        this.securityConfigMapper = securityConfigMapper;
        this.txTemplate = txTemplate;
        this.securityConfigAccessor = securityConfigAccessor;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    @AdminAudit(resourceType = "mcp_security", action = "update", resourceIdExpr = "'singleton'")
    public void updateSecurityConfig(McpSecurityConfigView view) {
        McpSecurityConfigView normalized = validator.validate(view);
        try {
            String json = objectMapper.writeValueAsString(normalized);
            txTemplate.executeWithoutResult(status -> securityConfigMapper.updateConfigJson(json));
            securityConfigAccessor.invalidate();
        } catch (JsonProcessingException e) {
            throw new ServiceException(ServiceErrorCode.SERIALIZATION_FAILED,
                    "MCP 安全配置序列化失败", e);
        }
    }

    public McpSecurityConfigView getSecurityConfig() {
        return securityConfigAccessor.get();
    }

}
