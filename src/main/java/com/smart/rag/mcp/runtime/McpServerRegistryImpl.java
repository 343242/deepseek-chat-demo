package com.smart.rag.mcp.runtime;

import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.infrastructure.fallback.FallbackEligibility;
import com.smart.rag.mcp.core.McpServer;
import com.smart.rag.mcp.core.McpServerRegistry;
import com.smart.rag.mcp.core.ServerId;
import com.smart.rag.mcp.policy.McpAuthorizer;
import com.smart.rag.mcp.policy.McpDescriptionSanitizer;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.smart.rag.mcp.mcpclient.McpToolUtils;
import com.smart.rag.mcp.mcpclient.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@link McpServerRegistry} 实现——per {@code McpSyncClient} 建 {@link McpServerImpl}（A1 拼合：调用面绑各自 client）。
 * <p>
 * 注入 {@code ObjectProvider<List<McpSyncClient>>} + provider + authz + 熔断器 + FallbackEligibility
 * （全可选/可解析：无 connections 空载不抛）。因 {@code spring.ai.mcp.client.initialized=false}，client 交付
 * 时<b>未握手</b> → 建期对每个 client {@code initialize()} + try/catch：
 * <ul>
 *   <li>成功 → 取 {@code serverInfo.name()} 作 {@link ServerId}，建 {@link McpServerImpl}(alive)</li>
 *   <li>失败（不可达/握手失败）→ 合成 id {@code unreachable-<i>} + {@code initError}（health=down），<b>不影响其他 client</b>（§11.4 隔离）</li>
 *   <li>多个 client 同 {@code serverInfo.name()} → 抛 {@link ServiceException}（配置错误，R-10）</li>
 * </ul>
 */
@Component
public class McpServerRegistryImpl implements McpServerRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpServerRegistryImpl.class);

    private final ObjectProvider<List<McpSyncClient>> clientsProvider;
    private final ObjectProvider<SyncMcpToolCallbackProvider> providerProvider;
    private final McpAuthorizer authorizer;
    private final McpCircuitBreakerRegistry circuitRegistry;
    private final FallbackEligibility fallbackEligibility;
    private final McpDescriptionSanitizer descriptionSanitizer;

    private final Map<ServerId, McpServer> servers = new LinkedHashMap<>();

    public McpServerRegistryImpl(ObjectProvider<List<McpSyncClient>> clientsProvider,
                                 ObjectProvider<SyncMcpToolCallbackProvider> providerProvider,
                                 McpAuthorizer authorizer,
                                 McpCircuitBreakerRegistry circuitRegistry,
                                 FallbackEligibility fallbackEligibility,
                                 McpDescriptionSanitizer descriptionSanitizer) {
        this.clientsProvider = clientsProvider;
        this.providerProvider = providerProvider;
        this.authorizer = authorizer;
        this.circuitRegistry = circuitRegistry;
        this.fallbackEligibility = fallbackEligibility;
        this.descriptionSanitizer = descriptionSanitizer;
    }

    @PostConstruct
    void init() {
        List<McpSyncClient> clients = clientsProvider.getIfAvailable(List::of);
        if (clients.isEmpty()) {
            log.info("MCP: 无配置连接，McpServerRegistry 空载（enabled=true 但无 server）");
            return;
        }
        SyncMcpToolCallbackProvider provider = providerProvider.getIfAvailable();
        Set<String> seenNames = new java.util.HashSet<>();
        for (int i = 0; i < clients.size(); i++) {
            McpSyncClient client = clients.get(i);
            String initError = null;
            ServerId id = null;
            try {
                if (!client.isInitialized()) {
                    client.initialize();
                }
                McpSchema.InitializeResult ir = client.getCurrentInitializationResult();
                if (ir != null && ir.serverInfo() != null && ir.serverInfo().name() != null
                        && !ir.serverInfo().name().isBlank()) {
                    // format 与 prefixGen 的 serverName 组件同源（均 McpToolUtils.format）→ id↔前缀↔yaml键 1:1
                    id = new ServerId(McpToolUtils.format(ir.serverInfo().name()));
                }
            } catch (Exception e) {
                initError = McpErrors.rootMessage(e);
                log.warn("MCP client #{} initialize 失败，标记 down（不影响其他 server）", i, e);
            }
            if (id == null) {
                // 握手未完成 → 无 serverInfo.name，用合成 id（health=down；真实名待恢复后由调用成功无法反查，已知局限）
                id = new ServerId("unreachable-" + i);
            } else if (!seenNames.add(id.value())) {
                throw new ServiceException(ServiceErrorCode.INTERNAL_ERROR,
                        "MCP 同名 server 配置冲突（serverInfo.name 重复）: " + id.value()
                                + "；请检查 spring.ai.mcp.client.*.connections 配置");
            }
            McpServerImpl server = new McpServerImpl(id, client, authorizer, circuitRegistry,
                    fallbackEligibility, provider, initError, descriptionSanitizer);
            servers.put(id, server);
            log.info("MCP server 已注册: id={} health={}", id.value(),
                    initError != null ? "down(initialize 失败)" : "alive");
        }
    }

    @Override
    public List<McpServer> list() {
        return List.copyOf(servers.values());
    }

    @Override
    public Optional<McpServer> find(ServerId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(servers.get(id));
    }
}
