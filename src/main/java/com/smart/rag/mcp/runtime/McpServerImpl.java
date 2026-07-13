package com.smart.rag.mcp.runtime;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.fallback.CircuitBreakerState;
import com.smart.rag.infrastructure.fallback.FallbackEligibility;
import com.smart.rag.mcp.core.McpArgs;
import com.smart.rag.mcp.core.McpIntent;
import com.smart.rag.mcp.core.McpPrompt;
import com.smart.rag.mcp.core.McpResource;
import com.smart.rag.mcp.core.McpServer;
import com.smart.rag.mcp.core.McpServerHealth;
import com.smart.rag.mcp.core.McpTools;
import com.smart.rag.mcp.core.McpResources;
import com.smart.rag.mcp.core.McpPrompts;
import com.smart.rag.mcp.core.McpTool;
import com.smart.rag.mcp.core.McpToolResult;
import com.smart.rag.mcp.core.ServerId;
import com.smart.rag.mcp.core.Subject;
import com.smart.rag.mcp.admin.entity.McpToolConfig;
import com.smart.rag.mcp.policy.McpAuthorizer;
import com.smart.rag.mcp.policy.McpDescriptionSanitizer;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.smart.rag.mcp.mcpclient.McpConnectionInfo;
import com.smart.rag.mcp.mcpclient.McpServerToolCallbacksAdapter;
import com.smart.rag.mcp.mcpclient.SyncMcpToolCallback;
import com.smart.rag.mcp.mcpclient.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.lang.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Per-connection implementation of the MCP core capabilities. */
public final class McpServerImpl implements McpServer {

    private static final Logger log = LoggerFactory.getLogger(McpServerImpl.class);

    private final ServerId id;
    private final McpSyncClient client;
    private final McpAuthorizer authorizer;
    private final McpCircuitBreakerRegistry circuitRegistry;
    private final FallbackEligibility fallbackEligibility;
    private final McpDescriptionSanitizer descriptionSanitizer;
    private final McpRemoteCallExecutor remoteCallExecutor;

    /** 聚合 provider（nullable：无 connections / starter 未建时）；tools 发现面委托它按前缀过滤。 */
    @Nullable
    private final SyncMcpToolCallbackProvider provider;

    /** Instance-local active flag: false after withdraw; callbacks captured before withdraw fail fast. */
    private volatile boolean active = true;

    private final McpTools tools = new McpToolsImpl();
    private final McpResources resources = new McpResourcesImpl();
    private final McpPrompts prompts = new McpPromptsImpl();

    McpServerImpl(ServerId id,
                  McpSyncClient client,
                  McpAuthorizer authorizer,
                  McpCircuitBreakerRegistry circuitRegistry,
                  FallbackEligibility fallbackEligibility,
                  @Nullable SyncMcpToolCallbackProvider provider,
                  McpDescriptionSanitizer descriptionSanitizer) {
        this.id = id;
        this.client = client;
        this.authorizer = authorizer;
        this.circuitRegistry = circuitRegistry;
        this.fallbackEligibility = fallbackEligibility;
        this.remoteCallExecutor = new McpRemoteCallExecutor(id, circuitRegistry, fallbackEligibility);
        this.provider = provider;
        this.descriptionSanitizer = descriptionSanitizer;
    }

    @Override
    public ServerId id() {
        return id;
    }

    @Override
    public McpServerHealth health() {
        CircuitBreakerState state = circuitRegistry.stateOf(id.value());
        return switch (state) {
            case CLOSED -> McpServerHealth.alive();
            case HALF_OPEN -> McpServerHealth.degraded("熔断器半开（探测中）");
            case OPEN -> McpServerHealth.down("熔断器打开");
        };
    }

    @Override
    public McpTools tools() {
        return tools;
    }

    @Override
    public McpResources resources() {
        return resources;
    }

    @Override
    public McpPrompts prompts() {
        return prompts;
    }

    private final class McpToolsImpl implements McpTools {

        @Override
        public List<McpTool> visibleTo(Subject subj, McpIntent intent) {
            if (!active || subj == null || !subj.isAuthenticated() || provider == null) {
                return List.of();
            }
            String prefix = id.value() + "_";
            ToolCallback[] callbacks;
            try {
                callbacks = provider.getToolCallbacks();
            } catch (Exception e) {
                // 发现失败（server 不可达等）→ 空集，不击穿；不计熔断（listTools 非调用面，不反映 server 健康）
                log.debug("MCP server 工具发现失败，返回空集，serverId={}, errorType={}",
                        id.value(), e.getClass().getSimpleName());
                return List.of();
            }
            List<McpTool> visible = new ArrayList<>();
            for (ToolCallback cb : callbacks) {
                ToolDefinition def = cb.getToolDefinition();
                String name = def.name();
                if (name == null || !name.startsWith(prefix)) {
                    continue;
                }
                if (!authorizer.canSee(subj, name, intent)) {
                    continue;
                }
                visible.add(new McpTool(name, descriptionSanitizer.sanitize(name, def.description()),
                        def.inputSchema()));
            }
            return visible;
        }

        @Override
        public McpToolResult call(String name, McpArgs args, Subject subj) {
            Objects.requireNonNull(args, "args");
            McpToolConfig toolConfig = authorizer.requireAuthorized(subj, name);
            String prefix = id.value() + "_";
            if (name == null || !name.startsWith(prefix)) {
                throw new ClientException(ClientErrorCode.BAD_REQUEST,
                        "工具名不属于本 MCP server（前缀不符）: " + name);
            }
            // 占位 server（握手失败）的友好错误
            if (client == null) {
                return McpToolResult.error("MCP Server 当前未连接，请稍后重试");
            }
            String rawName = toolConfig.getToolName();
            if (rawName == null || rawName.isBlank()) {
                throw new ClientException(ClientErrorCode.BAD_REQUEST, "MCP 工具原始名称无效");
            }
            String key = id.value();
            if (!circuitRegistry.isCallAllowed(key)) {
                // OPEN 快速失败：不打远端，降级为 tool error（不击穿 LLM）
                return McpToolResult.error("[circuit open] server " + key + " 熔断中，请稍后重试");
            }
            try {
                McpSchema.CallToolResult result = client.callTool(
                        new McpSchema.CallToolRequest(rawName, args.asMap()));
                circuitRegistry.recordSuccess(key);
                return McpSchemaMapper.toToolResult(result);
            } catch (Exception e) {
                if (fallbackEligibility.isEligible(e)) {
                    circuitRegistry.recordFailure(key); // 仅 C 类计熔断（A/B 经 FallbackEligibility 排除）
                } else {
                    // 非 eligible（NPE/ISE 编程错误）不计熔断，但必须释放 HALF_OPEN 探测槽——
                    // isCallAllowed 在 HALF_OPEN 态占 activeHalfOpenProbes，不释放会累计卡死
                    // HALF_OPEN 探测槽必须在非远端故障路径释放，避免后续探测永久被拒绝。
                    circuitRegistry.releaseProbe(key);
                }
                log.warn("MCP 工具调用失败并降级，toolName={}, errorType={}",
                        name, e.getClass().getSimpleName());
                return McpToolResult.error("MCP 工具调用失败，请稍后重试");
            }
        }
    }

    private final class McpResourcesImpl implements McpResources {

        @Override
        public McpResource read(URI uri, Subject subj) {
            Objects.requireNonNull(uri, "uri");
            if (subj == null || !subj.isAuthenticated()) {
                throw new ClientException(ClientErrorCode.FORBIDDEN, "MCP resource 读取：调用方主体未认证");
            }
            McpUriPolicy.requireAllowed(uri);
            McpSyncClient connected = requireConnected();
            return remoteCallExecutor.execute(new McpRemoteCallExecutor.Operation<>(
                    "resource 读取",
                    () -> connected.readResource(new McpSchema.ReadResourceRequest(uri.toString())),
                    result -> McpSchemaMapper.toResource(uri, result),
                    "MCP Resource 读取失败，请稍后重试",
                    () -> {}));
        }
    }

    private final class McpPromptsImpl implements McpPrompts {

        @Override
        public McpPrompt get(String name, McpArgs args, Subject subj) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(args, "args");
            if (subj == null || !subj.isAuthenticated()) {
                throw new ClientException(ClientErrorCode.FORBIDDEN, "MCP prompt 取回：调用方主体未认证");
            }
            McpSyncClient connected = requireConnected();
            return remoteCallExecutor.execute(new McpRemoteCallExecutor.Operation<>(
                    "prompt 获取",
                    () -> connected.getPrompt(new McpSchema.GetPromptRequest(name, args.asMap())),
                    result -> McpSchemaMapper.toPrompt(name, result),
                    "MCP Prompt 获取失败，请稍后重试",
                    () -> {}));
        }
    }

    public boolean hasClient() {
        return client != null;
    }

    /** Returns the underlying client, or null if this is a placeholder server. */
    @Nullable
    public McpSyncClient getClient() {
        return client;
    }

    public void closeQuietly() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.debug("MCP client close ignored, errorType={}", e.getClass().getSimpleName());
            }
        }
    }

    public boolean isActive() {
        return active;
    }

    public void markInactive() {
        this.active = false;
    }

    public void markActive() {
        this.active = true;
    }

    public java.util.List<io.modelcontextprotocol.spec.McpSchema.Tool> listToolsFromRemote() {
        McpSyncClient connected = requireConnected();
        try {
            return connected.listTools().tools();
        } catch (RuntimeException e) {
            throw new RemoteException(RemoteErrorCode.MCP_SERVER_UNREACHABLE,
                    "MCP Server 工具列表获取失败", e);
        }
    }

    private McpSyncClient requireConnected() {
        if (client == null) {
            throw new RemoteException(RemoteErrorCode.MCP_SERVER_UNREACHABLE,
                    "MCP Server 当前未连接，请稍后重试");
        }
        return client;
    }

    public List<ToolCallback> toolCallbacks(McpServerToolCallbacksAdapter.DiscoveryOptions options) {
        if (client == null) {
            return List.of();
        }
        McpConnectionInfo connInfo = McpConnectionInfo.builder()
                .clientCapabilities(client.getClientCapabilities())
                .clientInfo(client.getClientInfo())
                .initializeResult(client.getCurrentInitializationResult())
                .build();
        return client.listTools().tools().stream()
                .filter(tool -> options.filter().test(connInfo, tool))
                .<ToolCallback>map(tool -> SyncMcpToolCallback.builder()
                        .mcpClient(client)
                        .tool(tool)
                        .prefixedToolName(options.prefixGenerator().prefixedToolName(connInfo, tool))
                        .toolContextToMcpMetaConverter(options.metaConverter())
                        .build())
                .toList();
    }
}
