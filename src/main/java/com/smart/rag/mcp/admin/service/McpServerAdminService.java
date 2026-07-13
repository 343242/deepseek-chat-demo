package com.smart.rag.mcp.admin.service;

import com.smart.rag.infrastructure.audit.AdminAudit;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.security.HostSafetyValidator;
import com.smart.rag.mcp.admin.dto.UpdateServerRequest;
import com.smart.rag.mcp.admin.entity.McpServerConfig;
import com.smart.rag.mcp.admin.mapper.McpServerConfigMapper;
import com.smart.rag.mcp.admin.mapper.McpToolConfigMapper;
import com.smart.rag.mcp.mcpclient.McpToolUtils;
import com.smart.rag.mcp.runtime.McpBearerTokenCodec;
import com.smart.rag.mcp.runtime.McpBearerTokenValidator;
import com.smart.rag.mcp.runtime.McpConnectionRecoveryScheduler;
import com.smart.rag.mcp.runtime.McpDesiredStateHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Service
public class McpServerAdminService {

    private static final Logger log = LoggerFactory.getLogger(McpServerAdminService.class);

    private final McpServerConfigMapper serverConfigMapper;
    private final McpToolConfigMapper toolConfigMapper;
    private final TransactionTemplate txTemplate;
    private final McpServerRuntime runtime;
    private final HostSafetyValidator urlValidator;
    private final McpBearerTokenCodec tokenCodec;
    private final McpBearerTokenValidator tokenValidator;
    private final McpDesiredStateHasher desiredStateHasher;

    private final McpConnectionRecoveryScheduler scheduler;

    public McpServerAdminService(McpServerConfigMapper serverConfigMapper,
                                 McpToolConfigMapper toolConfigMapper,
                                 TransactionTemplate txTemplate,
                                 McpServerRuntime runtime,
                                 HostSafetyValidator urlValidator,
                                 McpBearerTokenCodec tokenCodec,
                                 McpBearerTokenValidator tokenValidator,
                                 McpDesiredStateHasher desiredStateHasher,
                                 McpConnectionRecoveryScheduler scheduler) {
        this.serverConfigMapper = serverConfigMapper;
        this.toolConfigMapper = toolConfigMapper;
        this.txTemplate = txTemplate;
        this.runtime = runtime;
        this.urlValidator = urlValidator;
        this.tokenCodec = tokenCodec;
        this.tokenValidator = tokenValidator;
        this.desiredStateHasher = desiredStateHasher;
        this.scheduler = scheduler;
    }

    public List<McpServerConfig> listServers() {
        return serverConfigMapper.selectList(null);
    }

    public McpServerConfig getServer(Long id) {
        McpServerConfig config = serverConfigMapper.selectById(id);
        if (config == null) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "MCP Server 不存在");
        }
        return config;
    }

    public String serverHealth(String serverId) {
        return runtime.health(serverId);
    }

    @AdminAudit(resourceType = "mcp_server", action = "update", resourceIdExpr = "#id")
    public McpServerConfig updateServer(Long id, UpdateServerRequest request) {
        McpServerConfig config = getServer(id);
        verifyVersion(request.version(), config.getVersion());

        boolean urlChanged = request.url() != null && !request.url().isBlank()
                && !request.url().trim().equals(config.getUrl());

        if (urlChanged) {
            String url = request.url().trim();
            urlValidator.validate(url);
            String newHash = desiredStateHasher.hash(url, config.getBearerTokenEncrypted(),
                    Boolean.TRUE.equals(config.getEnabled()));
            if (serverConfigMapper.updateDesiredUrl(config.getServerId(), url, newHash,
                    config.getVersion()) == 0) {
                throw optimisticConflict();
            }
            runtime.remove(config.getServerId());
            scheduler.wake(config.getServerId());
            return serverConfigMapper.selectById(id);
        } else {
            if (request.name() != null) {
                config.setName(request.name().trim());
            }
            if (request.description() != null) {
                config.setDescription(request.description().trim());
            }
            if (serverConfigMapper.updateById(config) == 0) {
                throw optimisticConflict();
            }
            return config;
        }
    }

    @AdminAudit(resourceType = "mcp_server", action = "create",
            resourceIdExpr = "#result.serverId", sensitiveFields = {"bearerToken"})
    public McpServerConfig createServer(CreateServerRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 128
                || !idempotencyKey.matches("[\\x20-\\x7E]+")) {
            throw new ClientException(ClientErrorCode.VALIDATION_ERROR,
                    "Idempotency-Key 必须为 1-128 个可打印 ASCII 字符");
        }
        String url = request.url().trim();
        urlValidator.validate(url);
        tokenValidator.validate(request.bearerToken());

        // Idempotency: check existing key first
        McpServerConfig existing = serverConfigMapper.selectByCreateRequestKey(idempotencyKey);
        if (existing != null) {
            if (sameCreatePayload(existing, request)) {
                return existing;
            }
            throw new ClientException(ClientErrorCode.CONFLICT,
                    "Idempotency-Key 已用于不同的 Server 配置");
        }

        McpServerConfig config = new McpServerConfig();
        config.setUrl(url);
        config.setName(trimToNull(request.name()));
        config.setDescription(trimToNull(request.description()));
        config.setAutoConnect(request.autoConnect() == null || request.autoConnect());
        config.setEnabled(true);
        config.setCreateRequestKey(idempotencyKey);
        config.setCatalogSynced(false);
        config.setConsecutiveFailures(0);
        if (request.bearerToken() != null && !request.bearerToken().isBlank()) {
            config.setBearerTokenEncrypted(tokenCodec.encode(request.bearerToken()));
        }

        // Two local writes in one transaction: insert -> assign mcp_<id> + hash -> update
        txTemplate.executeWithoutResult(status -> {
            config.setDesiredStateHash("0".repeat(64)); // passes CHECK ^[0-9a-f]{64}$ until real hash assigned
            serverConfigMapper.insert(config);
            config.setServerId(McpToolUtils.serverId(config.getId()));
            config.setDesiredStateHash(desiredStateHasher.hash(
                    url, config.getBearerTokenEncrypted(), true));
            config.setNextReconcileAt(java.time.OffsetDateTime.now());
            serverConfigMapper.updateById(config);
        });

        // Best-effort wake; DB due time ensures it is picked up even if rejected
        scheduler.wake(config.getServerId());

        log.info("MCP Server created (desired state committed): id={} serverId={}",
                config.getId(), config.getServerId());
        return config;
    }

    @AdminAudit(resourceType = "mcp_server", action = "delete", resourceIdExpr = "#id")
    public void deleteServer(Long id) {
        McpServerConfig config = serverConfigMapper.selectById(id);
        if (config == null) {
            return;
        }
        String serverId = config.getServerId();
        txTemplate.executeWithoutResult(status -> {
            serverConfigMapper.deleteById(id);
            if (serverId != null) {
                toolConfigMapper.deleteByServerId(serverId);
            }
        });
        if (serverId != null) {
            runtime.remove(serverId);
        }
    }
    public McpServerConfig enableServer(String serverId) {
        McpServerConfig config = requireServer(serverId);
        if (serverConfigMapper.enableAndScheduleReconcile(serverId, config.getVersion()) == 0) {
            throw optimisticConflict();
        }
        runtime.remove(serverId);
        scheduler.wake(serverId);
        return serverConfigMapper.selectByServerId(serverId);
    }

    @AdminAudit(resourceType = "mcp_server", action = "disable", resourceIdExpr = "#serverId")
    public McpServerConfig disableServer(String serverId) {
        McpServerConfig config = requireServer(serverId);
        if (serverConfigMapper.disableAndClearReconcile(serverId, config.getVersion()) == 0) {
            throw optimisticConflict();
        }
        toolConfigMapper.updateEnabledByServerId(serverId, false);
        runtime.remove(serverId);
        return serverConfigMapper.selectByServerId(serverId);
    }

    public McpServerConfig reconnectServer(String serverId) {
        McpServerConfig config = requireServer(serverId);
        if (serverConfigMapper.clearObservation(serverId, config.getVersion()) == 0) {
            throw optimisticConflict();
        }
        runtime.remove(serverId);
        scheduler.wake(serverId);
        return serverConfigMapper.selectByServerId(serverId);
    }

    @AdminAudit(resourceType = "mcp_server", action = "update_bearer_token",
            resourceIdExpr = "#serverId", sensitiveFields = {"bearerToken"})
    public McpServerConfig updateBearerToken(String serverId, String bearerToken) {
        tokenValidator.validate(bearerToken);
        McpServerConfig config = requireServer(serverId);
        String encrypted = bearerToken != null && !bearerToken.isBlank()
                ? tokenCodec.encode(bearerToken) : null;
        String newHash = desiredStateHasher.hash(config.getUrl(), encrypted,
                Boolean.TRUE.equals(config.getEnabled()));
        if (serverConfigMapper.updateDesiredToken(serverId, encrypted, newHash,
                config.getVersion()) == 0) {
            throw optimisticConflict();
        }
        runtime.remove(serverId);
        scheduler.wake(serverId);
        return serverConfigMapper.selectByServerId(serverId);
    }

    @AdminAudit(resourceType = "mcp_server", action = "refresh_tools", resourceIdExpr = "#serverId")
    public void refreshTools(String serverId) {
        McpServerConfig config = requireServer(serverId);
        if (serverConfigMapper.clearCatalogSynced(serverId, config.getVersion()) == 0) {
            throw optimisticConflict();
        }
        scheduler.wake(serverId);
    }

    /**
     * Idempotency payload comparison: URL equality + token presence (not value).
     * <p>
     * Token value is encrypted with a random IV, so byte-level comparison is not feasible
     * without decryption. A retry with a different token value but same Idempotency-Key will
     * return the existing row, keeping the original token. This is an accepted limitation:
     * operators who need to rotate the token must use the dedicated update-bearer-token endpoint.
     */
    private boolean sameCreatePayload(McpServerConfig existing, CreateServerRequest request) {
        if (!existing.getUrl().equals(request.url().trim())) return false;
        boolean existingToken = existing.getBearerTokenEncrypted() != null && !existing.getBearerTokenEncrypted().isBlank();
        boolean requestToken = request.bearerToken() != null && !request.bearerToken().isBlank();
        return existingToken == requestToken;
    }

    private McpServerConfig requireServer(String serverId) {
        McpServerConfig config = serverConfigMapper.selectByServerId(serverId);
        if (config == null) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "MCP Server 不存在");
        }
        return config;
    }

    private static void verifyVersion(Long requested, Long current) {
        if (requested != null && !requested.equals(current)) {
            throw optimisticConflict();
        }
    }

    private static ClientException optimisticConflict() {
        return new ClientException(ClientErrorCode.OPTIMISTIC_LOCK_CONFLICT, "MCP 配置已被修改，请刷新后重试");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
