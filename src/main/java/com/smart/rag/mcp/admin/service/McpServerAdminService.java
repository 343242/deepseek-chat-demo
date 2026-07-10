package com.smart.rag.mcp.admin.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.smart.rag.infrastructure.audit.AdminAudit;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.infrastructure.security.HostSafetyValidator;
import com.smart.rag.mcp.admin.dto.UpdateServerRequest;
import com.smart.rag.mcp.admin.entity.McpServerConfig;
import com.smart.rag.mcp.admin.mapper.McpServerConfigMapper;
import com.smart.rag.mcp.admin.mapper.McpToolConfigMapper;
import com.smart.rag.mcp.mcpclient.McpToolUtils;
import com.smart.rag.mcp.runtime.McpBearerTokenCodec;
import com.smart.rag.mcp.runtime.McpErrors;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;

@Service
public class McpServerAdminService {

    private static final Logger log = LoggerFactory.getLogger(McpServerAdminService.class);
    private static final Duration RECONNECT_COOLDOWN = Duration.ofSeconds(30);
    private final McpServerConfigMapper serverConfigMapper;
    private final McpToolConfigMapper toolConfigMapper;
    private final TransactionTemplate txTemplate;
    private final McpServerRuntime runtime;
    private final HostSafetyValidator urlValidator;
    private final McpBearerTokenCodec tokenCodec;
    private final McpToolAdminService toolAdminService;
    private final Cache<String, Long> reconnectCooldown = Caffeine.newBuilder()
            .expireAfterWrite(RECONNECT_COOLDOWN).maximumSize(100).build();

    public McpServerAdminService(McpServerConfigMapper serverConfigMapper,
                                 McpToolConfigMapper toolConfigMapper,
                                 TransactionTemplate txTemplate,
                                 McpServerRuntime runtime,
                                 HostSafetyValidator urlValidator,
                                 McpBearerTokenCodec tokenCodec,
                                 McpToolAdminService toolAdminService) {
        this.serverConfigMapper = serverConfigMapper;
        this.toolConfigMapper = toolConfigMapper;
        this.txTemplate = txTemplate;
        this.runtime = runtime;
        this.urlValidator = urlValidator;
        this.tokenCodec = tokenCodec;
        this.toolAdminService = toolAdminService;
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
    public void updateServer(Long id, UpdateServerRequest request) {
        McpServerConfig config = getServer(id);
        verifyVersion(request.version(), config.getVersion());
        if (request.url() != null && !request.url().isBlank()) {
            String url = request.url().trim();
            urlValidator.validate(url);
            config.setUrl(url);
        }
        if (request.name() != null) {
            config.setName(request.name().trim());
        }
        if (request.description() != null) {
            config.setDescription(request.description().trim());
        }
        if (serverConfigMapper.updateById(config) == 0) {
            throw optimisticConflict();
        }
    }

    @AdminAudit(resourceType = "mcp_server", action = "create",
            resourceIdExpr = "#result.id", sensitiveFields = {"bearerToken"})
    public McpServerConfig createServer(CreateServerRequest request) {
        String url = request.url().trim();
        urlValidator.validate(url);
        McpServerConfig config = new McpServerConfig();
        config.setUrl(url);
        config.setName(trimToNull(request.name()));
        config.setDescription(trimToNull(request.description()));
        config.setAutoConnect(request.autoConnect() == null || request.autoConnect());
        config.setEnabled(true);
        if (request.bearerToken() != null && !request.bearerToken().isBlank()) {
            config.setBearerTokenEncrypted(tokenCodec.encode(request.bearerToken()));
        }
        txTemplate.executeWithoutResult(status -> serverConfigMapper.insert(config));

        McpSyncClient client = null;
        boolean handedOff = false;
        try {
            client = runtime.connect(config);
            config.setServerId(deriveServerId(client));
            if (serverConfigMapper.updateById(config) == 0) {
                throw optimisticConflict();
            }
            runtime.add(config, client, null);
            handedOff = true;
            serverConfigMapper.markConnected(config.getServerId());
            toolAdminService.invalidate(config.getServerId());
        } catch (RemoteException e) {
            releaseFailedClient(config.getServerId(), client, handedOff);
            persistPlaceholder(config, e);
        } catch (ClientException | ServiceException e) {
            releaseFailedClient(config.getServerId(), client, handedOff);
            throw e;
        } catch (RuntimeException e) {
            releaseFailedClient(config.getServerId(), client, handedOff);
            throw new ServiceException(ServiceErrorCode.INTERNAL_ERROR,
                    "MCP Server 注册失败，请稍后重试", e);
        }
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
            toolAdminService.invalidate(serverId);
        }
    }

    @AdminAudit(resourceType = "mcp_server", action = "enable", resourceIdExpr = "#serverId")
    public void enableServer(String serverId) {
        requireServer(serverId);
        serverConfigMapper.updateEnabled(serverId, true);
        McpServerConfig refreshed = requireServer(serverId);
        connectAndAdd(refreshed);
        toolAdminService.invalidate(serverId);
    }

    @AdminAudit(resourceType = "mcp_server", action = "disable", resourceIdExpr = "#serverId")
    public void disableServer(String serverId) {
        txTemplate.executeWithoutResult(status -> {
            serverConfigMapper.updateEnabled(serverId, false);
            toolConfigMapper.updateEnabledByServerId(serverId, false);
        });
        runtime.remove(serverId);
        toolAdminService.invalidate(serverId);
    }

    @AdminAudit(resourceType = "mcp_server", action = "reconnect", resourceIdExpr = "#serverId")
    public void reconnectServer(String serverId) {
        if (reconnectCooldown.getIfPresent(serverId) != null) {
            throw new ClientException(ClientErrorCode.RATE_LIMITED, "MCP Server 重连过于频繁，请稍后重试");
        }
        reconnectCooldown.put(serverId, System.currentTimeMillis());
        McpServerConfig config = requireServer(serverId);
        McpSyncClient client = null;
        boolean handedOff = false;
        try {
            client = runtime.connect(config);
            runtime.replace(config, client);
            handedOff = true;
            serverConfigMapper.markConnected(serverId);
            toolAdminService.invalidate(serverId);
        } catch (RuntimeException e) {
            releaseFailedClient(serverId, client, handedOff);
            serverConfigMapper.updateInitError(serverId, McpErrors.safeSummary(e));
            throw new RemoteException(RemoteErrorCode.MCP_SERVER_UNREACHABLE,
                    "MCP Server 重连失败，请检查远端服务", e);
        }
    }

    @AdminAudit(resourceType = "mcp_server", action = "update_bearer_token",
            resourceIdExpr = "#serverId", sensitiveFields = {"bearerToken"})
    public void updateBearerToken(String serverId, String bearerToken) {
        McpServerConfig config = requireServer(serverId);
        String encrypted = tokenCodec.encode(bearerToken);
        txTemplate.executeWithoutResult(status -> {
            if (serverConfigMapper.updateBearerToken(serverId, encrypted, config.getVersion()) == 0) {
                throw optimisticConflict();
            }
        });
        McpServerConfig refreshed = requireServer(serverId);
        McpSyncClient client = null;
        boolean handedOff = false;
        try {
            client = runtime.connect(refreshed);
            runtime.replace(refreshed, client);
            handedOff = true;
            serverConfigMapper.markConnected(serverId);
        } catch (RuntimeException e) {
            releaseFailedClient(serverId, client, handedOff);
            serverConfigMapper.updateInitError(serverId, McpErrors.safeSummary(e));
            throw new RemoteException(RemoteErrorCode.MCP_SERVER_UNREACHABLE,
                    "Bearer Token 更新后重连失败，请检查远端服务", e);
        }
    }
    void initializeAtStartup(McpServerConfig config) {
        connectAndAdd(config);
    }

    private void connectAndAdd(McpServerConfig config) {
        McpSyncClient client = null;
        boolean handedOff = false;
        try {
            client = runtime.connect(config);
            runtime.add(config, client, null);
            handedOff = true;
            serverConfigMapper.markConnected(config.getServerId());
        } catch (RuntimeException e) {
            releaseFailedClient(config.getServerId(), client, handedOff);
            String message = McpErrors.safeSummary(e);
            serverConfigMapper.updateInitError(config.getServerId(), message);
            runtime.add(config, null, message);
        }
    }
    private void persistPlaceholder(McpServerConfig config, RuntimeException failure) {
        String syntheticId = "unreachable-" + config.getId();
        String message = McpErrors.safeSummary(failure);
        config.setServerId(syntheticId);
        config.setInitError(message);
        serverConfigMapper.updateById(config);
        serverConfigMapper.updateInitError(syntheticId, message);
        runtime.add(config, null, message);
        log.warn("MCP Server 创建后初始化失败，id={}", config.getId());
    }
    private McpServerConfig requireServer(String serverId) {
        McpServerConfig config = serverConfigMapper.selectByServerId(serverId);
        if (config == null) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "MCP Server 不存在");
        }
        return config;
    }

    private static String deriveServerId(McpSyncClient client) {
        McpSchema.InitializeResult result = client.getCurrentInitializationResult();
        if (result == null || result.serverInfo() == null) {
            throw new RemoteException(RemoteErrorCode.MCP_SERVER_UNREACHABLE,
                    "MCP Server 未返回有效身份信息");
        }
        try {
            return McpToolUtils.canonicalServerId(result.serverInfo().name());
        } catch (ClientException e) {
            throw new RemoteException(RemoteErrorCode.MCP_SERVER_UNREACHABLE, "MCP Server 未返回有效身份信息", e);
        }
    }

    private static void verifyVersion(Long requested, Long current) {
        if (requested != null && !requested.equals(current)) {
            throw optimisticConflict();
        }
    }

    private static ClientException optimisticConflict() {
        return new ClientException(ClientErrorCode.OPTIMISTIC_LOCK_CONFLICT, "MCP 配置已被修改，请刷新后重试");
    }

    private void releaseFailedClient(String serverId, McpSyncClient client, boolean handedOff) {
        if (handedOff && serverId != null) {
            runtime.remove(serverId);
        } else {
            runtime.close(client);
        }
    }
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
