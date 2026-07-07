package com.smart.rag.mcp.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.smart.rag.infrastructure.audit.AdminAudit;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.infrastructure.security.HostSafetyValidator;
import com.smart.rag.infrastructure.security.SecretCipher;
import com.smart.rag.mcp.admin.entity.McpSecurityConfigView;
import com.smart.rag.mcp.admin.entity.McpServerConfig;
import com.smart.rag.mcp.admin.entity.McpToolConfig;
import com.smart.rag.mcp.admin.mapper.McpSecurityConfigMapper;
import com.smart.rag.mcp.admin.mapper.McpServerConfigMapper;
import com.smart.rag.mcp.admin.mapper.McpToolConfigMapper;
import com.smart.rag.mcp.config.McpClientTransportConfiguration.McpClientTransportProperties;
import com.smart.rag.mcp.core.McpServer;
import com.smart.rag.mcp.core.ServerId;
import com.smart.rag.mcp.mcpclient.McpToolUtils;
import com.smart.rag.mcp.mcpclient.SyncMcpToolCallbackProvider;
import com.smart.rag.mcp.policy.McpSecurityProperties;
import com.smart.rag.mcp.runtime.McpClientFactory;
import com.smart.rag.mcp.runtime.McpErrors;
import com.smart.rag.mcp.runtime.McpServerRegistryAdmin;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Admin 服务——DB 驱动的 CRUD + 运行时控制 + 启动 bootstrap（v4 完整修复版）。
 * <p>
 * <b>关键设计</b>：
 * <ul>
 *   <li>{@code implements ApplicationRunner}：启动初始化逻辑放 {@link #run}，打破循环依赖</li>
 *   <li>所有写方法标注 {@link AdminAudit}，AOP 自动捕获操作者/IP/耗时</li>
 *   <li>软失败：DB commit 成功后运行时失败时 UPDATE {@code init_error}，不回滚 DB</li>
 *   <li>重连限流：per-serverId 30s cooldown（{@link ClientErrorCode#RATE_LIMITED}）</li>
 *   <li>乐观锁：{@code updateServer} / {@code updateBearerToken} 用 {@code version} 条件</li>
 * </ul>
 * <p>
 * <b>v4 B1 异常替换</b>：{@code RATE_LIMITED} → {@link ClientException}；
 * init/reconnect/bearer-rebuild 失败 → {@link RemoteException}（{@link RemoteErrorCode#MCP_SERVER_UNREACHABLE}）；
 * 乐观锁冲突 → {@link ClientException}（{@link ClientErrorCode#OPTIMISTIC_LOCK_CONFLICT}）。
 * <p>
 * <b>v4 C7 自调用约束</b>：本类所有 {@code @AdminAudit} 方法之间<b>禁止</b>直接相互调用。
 */
@Service
public class McpAdminService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(McpAdminService.class);
    private static final Duration RECONNECT_COOLDOWN = Duration.ofSeconds(30);

    private final McpServerConfigMapper serverConfigMapper;
    private final McpToolConfigMapper toolConfigMapper;
    private final McpSecurityConfigMapper securityConfigMapper;
    private final TransactionTemplate txTemplate;
    private final McpClientFactory clientFactory;
    private final McpServerRegistryAdmin registryAdmin;
    private final com.smart.rag.mcp.core.McpServerRegistry registryRead;
    private final HostSafetyValidator urlValidator;
    private final SecretCipher secretCipher;
    private final SyncMcpToolCallbackProvider toolCallbackProvider;
    private final McpSecurityConfigAccessor securityConfigAccessor;
    private final ObjectMapper objectMapper;
    private final McpClientTransportProperties transportProps;
    private final McpSecurityProperties securityProps;

    private final Cache<String, List<McpToolConfig>> toolListCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10)).maximumSize(100).build();
    private final Cache<String, Boolean> toolEnabledCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10)).maximumSize(10_000).build();
    private final Cache<String, Long> reconnectCooldown = Caffeine.newBuilder()
            .expireAfterWrite(RECONNECT_COOLDOWN).maximumSize(100).build();

    public McpAdminService(McpServerConfigMapper serverConfigMapper,
                           McpToolConfigMapper toolConfigMapper,
                           McpSecurityConfigMapper securityConfigMapper,
                           TransactionTemplate txTemplate,
                           McpClientFactory clientFactory,
                           McpServerRegistryAdmin registryAdmin,
                           com.smart.rag.mcp.core.McpServerRegistry registryRead,
                           HostSafetyValidator urlValidator,
                           SecretCipher secretCipher,
                           SyncMcpToolCallbackProvider toolCallbackProvider,
                           McpSecurityConfigAccessor securityConfigAccessor,
                           ObjectMapper objectMapper,
                           McpClientTransportProperties transportProps,
                           McpSecurityProperties securityProps) {
        this.serverConfigMapper = serverConfigMapper;
        this.toolConfigMapper = toolConfigMapper;
        this.securityConfigMapper = securityConfigMapper;
        this.txTemplate = txTemplate;
        this.clientFactory = clientFactory;
        this.registryAdmin = registryAdmin;
        this.registryRead = registryRead;
        this.urlValidator = urlValidator;
        this.secretCipher = secretCipher;
        this.toolCallbackProvider = toolCallbackProvider;
        this.securityConfigAccessor = securityConfigAccessor;
        this.objectMapper = objectMapper;
        this.transportProps = transportProps;
        this.securityProps = securityProps;
    }

    // ==================== ApplicationRunner：启动 bootstrap + init ====================

    @Override
    public void run(ApplicationArguments args) {
        if (serverConfigMapper.selectCount(null) == 0) {
            bootstrapFromYaml();
        }
        initFromDb();
    }

    /** 首次启动从 yaml 导入 DB（v4 C5：host 粒度限制，bootstrap 后 ADMIN per-server 覆盖） */
    private void bootstrapFromYaml() {
        McpClientTransportProperties.StreamableHttp streamable = transportProps.getStreamableHttp();
        if (streamable == null || streamable.getConnections() == null || streamable.getConnections().isEmpty()) {
            log.info("MCP bootstrap: 无 yaml connections，跳过");
            return;
        }
        Map<String, String> bearerByHost = securityProps.getBearerTokens() != null
                ? securityProps.getBearerTokens() : Map.of();
        long rowId = 1;
        for (Map.Entry<String, McpClientTransportProperties.ConnectionParameters> entry
                : streamable.getConnections().entrySet()) {
            McpClientTransportProperties.ConnectionParameters conn = entry.getValue();
            if (conn == null || conn.getUrl() == null || conn.getUrl().isBlank()) {
                continue;
            }
            McpServerConfig row = new McpServerConfig();
            row.setServerId(null);
            row.setUrl(conn.getUrl());
            row.setName(entry.getKey());
            row.setEnabled(true);
            row.setAutoConnect(true);
            String host = safeHost(conn.getUrl());
            String token = host != null ? bearerByHost.get(host) : null;
            if (token != null && !token.isBlank() && secretCipher.isAvailable()) {
                row.setBearerTokenEncrypted(encryptToken(token));
            }
            serverConfigMapper.insert(row);
            rowId++;
        }
        log.info("MCP bootstrap: imported {} server(s) from yaml", rowId - 1);
    }

    /** 从 DB 加载所有 enabled server，逐个 createClient + addServer（fail-soft） */
    private void initFromDb() {
        List<McpServerConfig> enabled = serverConfigMapper.selectAllEnabled();
        if (enabled.isEmpty()) {
            log.info("MCP init: 无 enabled server（registry 保持空）");
            return;
        }
        for (McpServerConfig config : enabled) {
            try {
                McpSyncClient client = clientFactory.createClient(config);
                registryAdmin.addServer(config, client, null);
                serverConfigMapper.updateInitError(config.getServerId(), null);
            } catch (Exception e) {
                String errMsg = McpErrors.rootMessage(e);
                log.warn("MCP server {} init failed: {}", config.getServerId(), errMsg);
                serverConfigMapper.updateInitError(config.getServerId(), errMsg);
                registryAdmin.addServer(config, null, errMsg);
            }
        }
    }

    // ==================== Server CRUD（全部 @AdminAudit）====================

    public List<McpServerConfig> listServers() {
        return serverConfigMapper.selectList(null);
    }

    public McpServerConfig getServer(Long id) {
        McpServerConfig c = serverConfigMapper.selectById(id);
        if (c == null) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "server not found: " + id);
        }
        return c;
    }

    public String serverHealth(String serverId) {
        return registryRead.find(new ServerId(serverId))
                .map(McpServer::health)
                .map(h -> h.status().name())
                .orElse("UNKNOWN");
    }

    @AdminAudit(resourceType = "mcp_server", action = "update", resourceIdExpr = "#id")
    public void updateServer(Long id, com.smart.rag.mcp.admin.dto.UpdateServerRequest request) {
        McpServerConfig config = serverConfigMapper.selectById(id);
        if (config == null) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "server not found: " + id);
        }
        if (request.version() != null && !request.version().equals(config.getVersion())) {
            throw new ClientException(ClientErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                    "concurrent modification of server: " + id);
        }
        if (request.url() != null && !request.url().isBlank()) {
            urlValidator.validate(request.url());
            config.setUrl(request.url());
        }
        if (request.name() != null) {
            config.setName(request.name());
        }
        if (request.description() != null) {
            config.setDescription(request.description());
        }
        int rows = serverConfigMapper.updateById(config);
        if (rows == 0) {
            throw new ClientException(ClientErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                    "concurrent modification of server: " + id);
        }
    }

    @AdminAudit(resourceType = "mcp_server", action = "create",
            resourceIdExpr = "#result.id", sensitiveFields = {"bearerToken"})
    public McpServerConfig createServer(CreateServerRequest request) {
        urlValidator.validate(request.url());
        McpServerConfig config = new McpServerConfig();
        config.setUrl(request.url());
        config.setName(request.name());
        config.setDescription(request.description());
        config.setAutoConnect(request.autoConnect() == null || request.autoConnect());
        config.setEnabled(true);
        if (request.bearerToken() != null && !request.bearerToken().isBlank()
                && secretCipher.isAvailable()) {
            config.setBearerTokenEncrypted(encryptToken(request.bearerToken()));
        }

        txTemplate.executeWithoutResult(status -> serverConfigMapper.insert(config));

        try {
            McpSyncClient client = clientFactory.createClient(config);
            String derivedId = deriveServerId(client);
            config.setServerId(derivedId);
            serverConfigMapper.updateById(config);
            registryAdmin.addServer(config, client, null);
            invalidateToolCache(derivedId);
        } catch (Exception e) {
            String errMsg = McpErrors.rootMessage(e);
            String syntheticId = "unreachable-" + config.getId();
            config.setServerId(syntheticId);
            serverConfigMapper.updateById(config);
            serverConfigMapper.updateInitError(syntheticId, errMsg);
            registryAdmin.addServer(config, null, errMsg);
            log.warn("MCP createServer id={} init failed: {}", config.getId(), errMsg);
        }
        return config;
    }

    @AdminAudit(resourceType = "mcp_server", action = "delete", resourceIdExpr = "#id")
    public void deleteServer(Long id) {
        McpServerConfig config = serverConfigMapper.selectById(id);
        if (config == null) {
            return;
        }
        String sid = config.getServerId();
        txTemplate.executeWithoutResult(status -> {
            serverConfigMapper.deleteById(id);
            if (sid != null) {
                toolConfigMapper.deleteByServerId(sid);
            }
        });
        if (sid != null) {
            registryAdmin.removeServer(new ServerId(sid));
            invalidateToolCache(sid);
        }
    }

    @AdminAudit(resourceType = "mcp_server", action = "enable", resourceIdExpr = "#serverId")
    public void enableServer(String serverId) {
        McpServerConfig config = serverConfigMapper.selectByServerId(serverId);
        if (config == null) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "server not found: " + serverId);
        }
        txTemplate.executeWithoutResult(status ->
                serverConfigMapper.updateEnabled(serverId, true));
        McpServerConfig refreshed = serverConfigMapper.selectByServerId(serverId);
        try {
            McpSyncClient client = clientFactory.createClient(refreshed);
            registryAdmin.addServer(refreshed, client, null);
            serverConfigMapper.updateInitError(serverId, null);
            invalidateToolCache(serverId);
        } catch (Exception e) {
            String errMsg = McpErrors.rootMessage(e);
            serverConfigMapper.updateInitError(serverId, errMsg);
            registryAdmin.addServer(refreshed, null, errMsg);
        }
    }

    @AdminAudit(resourceType = "mcp_server", action = "disable", resourceIdExpr = "#serverId")
    public void disableServer(String serverId) {
        txTemplate.executeWithoutResult(status -> {
            serverConfigMapper.updateEnabled(serverId, false);
            toolConfigMapper.updateEnabledByServerId(serverId, false);
        });
        registryAdmin.removeServer(new ServerId(serverId));
        invalidateToolCache(serverId);
    }

    @AdminAudit(resourceType = "mcp_server", action = "reconnect", resourceIdExpr = "#serverId")
    public void reconnectServer(String serverId) {
        Long lastRun = reconnectCooldown.getIfPresent(serverId);
        if (lastRun != null) {
            throw new ClientException(ClientErrorCode.RATE_LIMITED,
                    "MCP server " + serverId + " reconnect cooldown (30s)");
        }
        reconnectCooldown.put(serverId, System.currentTimeMillis());

        McpServerConfig config = serverConfigMapper.selectByServerId(serverId);
        if (config == null) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "server not found: " + serverId);
        }
        try {
            McpSyncClient client = clientFactory.createClient(config);
            registryAdmin.replaceServer(config, client);
            serverConfigMapper.updateInitError(serverId, null);
            invalidateToolCache(serverId);
        } catch (Exception e) {
            String errMsg = McpErrors.rootMessage(e);
            serverConfigMapper.updateInitError(serverId, errMsg);
            throw new RemoteException(RemoteErrorCode.MCP_SERVER_UNREACHABLE,
                    "MCP reconnect failed: " + errMsg, e);
        }
    }

    @AdminAudit(resourceType = "mcp_server", action = "update_bearer_token",
            resourceIdExpr = "#serverId", sensitiveFields = {"bearerToken"})
    public void updateBearerToken(String serverId, String bearerToken) {
        McpServerConfig config = serverConfigMapper.selectByServerId(serverId);
        if (config == null) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "server not found: " + serverId);
        }
        if (!secretCipher.isAvailable()) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST,
                    "SecretCipher 不可用（master-key 缺失），无法加密 bearer token");
        }
        String encrypted = encryptToken(bearerToken);
        int rows;
        txTemplate.executeWithoutResult(status -> {
            int r = serverConfigMapper.updateBearerToken(serverId, encrypted, config.getVersion());
            if (r == 0) {
                throw new ClientException(ClientErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                        "concurrent modification of server: " + serverId);
            }
        });
        McpServerConfig refreshed = serverConfigMapper.selectByServerId(serverId);
        try {
            McpSyncClient client = clientFactory.createClient(refreshed);
            registryAdmin.replaceServer(refreshed, client);
            serverConfigMapper.updateInitError(serverId, null);
        } catch (Exception e) {
            String errMsg = McpErrors.rootMessage(e);
            serverConfigMapper.updateInitError(serverId, errMsg);
            throw new RemoteException(RemoteErrorCode.MCP_SERVER_UNREACHABLE,
                    "client rebuild after bearer update failed: " + errMsg, e);
        }
    }

    // ==================== 工具管理 ====================

    @AdminAudit(resourceType = "mcp_tool", action = "refresh_tools", resourceIdExpr = "#serverId")
    public void refreshTools(String serverId) {
        McpServer server = registryRead.find(new ServerId(serverId))
                .orElseThrow(() -> new ClientException(ClientErrorCode.BAD_REQUEST,
                        "server not found in registry: " + serverId));
        if (server instanceof com.smart.rag.mcp.runtime.McpServerImpl impl && impl.initError() != null) {
            throw new RemoteException(RemoteErrorCode.MCP_SERVER_UNREACHABLE,
                    "server down, cannot refresh tools: " + impl.initError());
        }
        List<McpSchema.Tool> remoteTools = listRemoteTools(serverId);
        txTemplate.executeWithoutResult(status -> {
            for (McpSchema.Tool tool : remoteTools) {
                upsertToolConfig(serverId, tool);
            }
        });
        invalidateToolCache(serverId);
        toolCallbackProvider.invalidateCache();
    }

    public List<McpToolConfig> listTools(String serverId) {
        return toolListCache.get(serverId, k -> toolConfigMapper.selectByServerId(k));
    }

    @AdminAudit(resourceType = "mcp_tool", action = "enable", resourceIdExpr = "#toolConfigId")
    public void enableTool(Long toolConfigId) {
        setToolEnabled(toolConfigId, true);
    }

    @AdminAudit(resourceType = "mcp_tool", action = "disable", resourceIdExpr = "#toolConfigId")
    public void disableTool(Long toolConfigId) {
        setToolEnabled(toolConfigId, false);
    }

    @AdminAudit(resourceType = "mcp_tool", action = "update", resourceIdExpr = "#toolConfigId")
    public void updateTool(Long toolConfigId, com.smart.rag.mcp.admin.dto.UpdateToolRequest request) {
        McpToolConfig tool = toolConfigMapper.selectById(toolConfigId);
        if (tool == null) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "tool not found: " + toolConfigId);
        }
        if (request.version() != null && !request.version().equals(tool.getVersion())) {
            throw new ClientException(ClientErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                    "concurrent modification of tool: " + toolConfigId);
        }
        if (request.enabled() != null) {
            tool.setEnabled(request.enabled());
        }
        if (request.intent() != null) {
            tool.setIntent(request.intent());
        }
        if (request.risk() != null) {
            tool.setRisk(request.risk());
        }
        if (request.descriptionOverride() != null) {
            tool.setDescriptionOverride(request.descriptionOverride());
        }
        int rows = toolConfigMapper.updateById(tool);
        if (rows == 0) {
            throw new ClientException(ClientErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                    "concurrent modification of tool: " + toolConfigId);
        }
        toolEnabledCache.invalidateAll();
        toolCallbackProvider.invalidateCache();
    }

    @AdminAudit(resourceType = "mcp_tool", action = "batch_enable")
    public void batchEnableTools(List<Long> ids) {
        txTemplate.executeWithoutResult(status ->
                toolConfigMapper.batchUpdateEnabled(ids, true));
        toolEnabledCache.invalidateAll();
        toolCallbackProvider.invalidateCache();
    }

    @AdminAudit(resourceType = "mcp_tool", action = "batch_disable")
    public void batchDisableTools(List<Long> ids) {
        txTemplate.executeWithoutResult(status ->
                toolConfigMapper.batchUpdateEnabled(ids, false));
        toolEnabledCache.invalidateAll();
        toolCallbackProvider.invalidateCache();
    }

    private void setToolEnabled(Long toolConfigId, boolean enabled) {
        McpToolConfig tool = toolConfigMapper.selectById(toolConfigId);
        if (tool == null) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "tool not found: " + toolConfigId);
        }
        tool.setEnabled(enabled);
        int rows = toolConfigMapper.updateById(tool);
        if (rows == 0) {
            throw new ClientException(ClientErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                    "concurrent modification of tool: " + toolConfigId);
        }
        toolEnabledCache.invalidateAll();
        toolCallbackProvider.invalidateCache();
    }

    /** DatabaseToolFilter 调用；三态：null=未入库，true/false=入库且启用/禁用 */
    public Boolean isToolEnabled(String prefixedToolName) {
        return toolEnabledCache.get(prefixedToolName, k -> {
            McpToolConfig tool = toolConfigMapper.selectByPrefixedName(k);
            return tool == null ? null : Boolean.TRUE.equals(tool.getEnabled());
        });
    }

    // ==================== 安全配置 ====================

    @AdminAudit(resourceType = "mcp_security", action = "update", resourceIdExpr = "'singleton'")
    public void updateSecurityConfig(McpSecurityConfigView view) {
        try {
            String json = objectMapper.writeValueAsString(view);
            txTemplate.executeWithoutResult(status ->
                    securityConfigMapper.updateConfigJson(json));
            securityConfigAccessor.invalidate();
        } catch (Exception e) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST,
                    "security config update failed: " + e.getMessage(), e);
        }
    }

    public McpSecurityConfigView getSecurityConfig() {
        return securityConfigAccessor.get();
    }

    // ==================== helper ====================

    private String encryptToken(String plain) {
        com.smart.rag.infrastructure.security.SecretCipher.CipherText ct = secretCipher.encrypt(plain);
        return new String(ct.cipher(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private String deriveServerId(McpSyncClient client) {
        McpSchema.InitializeResult ir = client.getCurrentInitializationResult();
        if (ir == null || ir.serverInfo() == null || ir.serverInfo().name() == null
                || ir.serverInfo().name().isBlank()) {
            throw new IllegalStateException("serverInfo.name missing after initialize");
        }
        return McpToolUtils.format(ir.serverInfo().name());
    }

    private List<McpSchema.Tool> listRemoteTools(String serverId) {
        McpServer server = registryRead.find(new ServerId(serverId))
                .orElseThrow(() -> new ClientException(ClientErrorCode.BAD_REQUEST,
                        "server not in registry: " + serverId));
        if (server instanceof com.smart.rag.mcp.runtime.McpServerImpl impl) {
            return impl.listToolsFromRemote();
        }
        return List.of();
    }

    private void upsertToolConfig(String serverId, McpSchema.Tool tool) {
        String prefixed = McpToolUtils.prefixedToolName(serverId, tool.name());
        McpToolConfig existing = toolConfigMapper.selectByPrefixedName(prefixed);
        if (existing == null) {
            McpToolConfig row = new McpToolConfig();
            row.setServerId(serverId);
            row.setToolName(tool.name());
            row.setPrefixedToolName(prefixed);
            row.setDescription(tool.description());
            row.setEnabled(false);
            row.setRisk("low");
            toolConfigMapper.insert(row);
        }
    }

    private void invalidateToolCache(String serverId) {
        toolListCache.invalidate(serverId);
        toolEnabledCache.invalidateAll();
    }

    private static String safeHost(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    public LocalDateTime now() {
        return LocalDateTime.now();
    }
}
