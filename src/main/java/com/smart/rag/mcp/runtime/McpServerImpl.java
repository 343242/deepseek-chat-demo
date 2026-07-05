package com.smart.rag.mcp.runtime;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
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
import com.smart.rag.mcp.policy.McpAuthorizer;
import com.smart.rag.mcp.policy.McpDescriptionSanitizer;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.smart.rag.mcp.mcpclient.McpConnectionInfo;
import com.smart.rag.mcp.mcpclient.McpServerToolCallbacksAdapter;
import com.smart.rag.mcp.mcpclient.McpToolFilter;
import com.smart.rag.mcp.mcpclient.McpToolNamePrefixGenerator;
import com.smart.rag.mcp.mcpclient.SyncMcpToolCallback;
import com.smart.rag.mcp.mcpclient.SyncMcpToolCallbackProvider;
import com.smart.rag.mcp.mcpclient.ToolContextToMcpMetaConverter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.lang.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link McpServer} 内核实现（package-private，由 {@link McpServerRegistryImpl} per-client 构建）。
 * <p>
 * <b>A1 拼合</b>：发现面（{@link #tools()}）委托聚合 {@code SyncMcpToolCallbackProvider} 按本 server 前缀
 * （{@code id() + "_"}）过滤产出；调用面（call/resources/prompts）绑本 server 的 {@link McpSyncClient}。
 * <p>
 * <b>熔断</b>（design D-4）：直接经 {@link McpCircuitBreakerRegistry}（基类 {@code AbstractCircuitBreakerRegistry}）
 * 驱动 {@code isCallAllowed/recordSuccess/recordFailure/stateOf}；A/B/C 计数过滤复用通用
 * {@link FallbackEligibility}。无 per-call retry（design D-1，retry 待后续通用化）。
 * <p>
 * <b>fail-soft</b>：{@code call()}（LLM 出口）catch 服务器异常 → 降级 {@code McpToolResult.error}（不击穿 LLM，
 * §11.4）；{@code read()}/{@code get()}（路径 C，业务出口）抛 {@link RemoteException}（业务自处理）。
 * authz 拒绝（A 类）一律抛 {@link ClientException} 传播（AC3，不降级）。
 */
final class McpServerImpl implements McpServer, McpServerToolCallbacksAdapter {

    private static final Logger log = LoggerFactory.getLogger(McpServerImpl.class);

    /** URI scheme 禁用清单（注入向量防御占位；完整可配置白名单留 Phase 3 路径 C 接入）。 */
    private static final Set<String> BLOCKED_URI_SCHEMES = Set.of("jar", "netdoc", "ldap", "jndi", "dns");

    private final ServerId id;
    private final McpSyncClient client;
    private final McpAuthorizer authorizer;
    private final McpCircuitBreakerRegistry circuitRegistry;
    private final FallbackEligibility fallbackEligibility;
    private final McpDescriptionSanitizer descriptionSanitizer;

    /** 聚合 provider（nullable：无 connections / starter 未建时）；tools 发现面委托它按前缀过滤。 */
    @Nullable
    private final SyncMcpToolCallbackProvider provider;

    /**
     * 启动期 initialize 失败信息（null=已就绪）；非空时 health=down、visibleTo 返回空（design D-6）。
     * <p>调用成功时置 null（best-effort 恢复信号，M5）：{@code volatile} 仅保证可见性、不保证"读-判定-写"原子；
     * 瞬时抖动（health 读到旧值后另一线程清空）无副作用——至多多观察一次 down。
     */
    @Nullable
    private volatile String initError;

    private final McpTools tools = new McpToolsImpl();
    private final McpResources resources = new McpResourcesImpl();
    private final McpPrompts prompts = new McpPromptsImpl();

    McpServerImpl(ServerId id,
                  McpSyncClient client,
                  McpAuthorizer authorizer,
                  McpCircuitBreakerRegistry circuitRegistry,
                  FallbackEligibility fallbackEligibility,
                  @Nullable SyncMcpToolCallbackProvider provider,
                  @Nullable String initError,
                  McpDescriptionSanitizer descriptionSanitizer) {
        this.id = id;
        this.client = client;
        this.authorizer = authorizer;
        this.circuitRegistry = circuitRegistry;
        this.fallbackEligibility = fallbackEligibility;
        this.provider = provider;
        this.initError = initError;
        this.descriptionSanitizer = descriptionSanitizer;
    }

    @Override
    public ServerId id() {
        return id;
    }

    @Override
    public McpServerHealth health() {
        String err = initError;
        if (err != null) {
            return McpServerHealth.down("initialize 失败: " + err);
        }
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

    // ==================== McpTools（出口① LLM） ====================

    private final class McpToolsImpl implements McpTools {

        @Override
        public List<McpTool> visibleTo(Subject subj, McpIntent intent) {
            if (initError != null || subj == null || !subj.isAuthenticated() || provider == null) {
                return List.of();
            }
            String prefix = id.value() + "_";
            ToolCallback[] callbacks;
            try {
                callbacks = provider.getToolCallbacks();
            } catch (Exception e) {
                // 发现失败（server 不可达等）→ 空集，不击穿；不计熔断（listTools 非调用面，不反映 server 健康）
                log.debug("MCP server [{}] 工具发现失败，返回空集", id.value(), e);
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
            authorizer.requireAuthorized(subj, name); // A 类硬 authz 兜底，propagate（AC3）
            String prefix = id.value() + "_";
            if (name == null || !name.startsWith(prefix)) {
                throw new ClientException(ClientErrorCode.BAD_REQUEST,
                        "工具名不属于本 MCP server（前缀不符）: " + name);
            }
            // 占位 server（握手失败）的友好错误（v4 修复 1.5）
            if (client == null) {
                String err = initError != null ? initError : "MCP server 未连接";
                return McpToolResult.error("[mcp disconnected] " + err);
            }
            String rawName = name.substring(prefix.length());
            String key = id.value();
            if (!circuitRegistry.isCallAllowed(key)) {
                // OPEN 快速失败：不打远端，降级为 tool error（不击穿 LLM）
                return McpToolResult.error("[circuit open] server " + key + " 熔断中，请稍后重试");
            }
            try {
                McpSchema.CallToolResult result = client.callTool(
                        new McpSchema.CallToolRequest(rawName, args.asMap()));
                circuitRegistry.recordSuccess(key);
                initError = null; // 调用成功 → 视为恢复
                return toToolResult(result);
            } catch (Exception e) {
                if (fallbackEligibility.isEligible(e)) {
                    circuitRegistry.recordFailure(key); // 仅 C 类计熔断（A/B 经 FallbackEligibility 排除）
                } else {
                    // 非 eligible（NPE/ISE 编程错误）不计熔断，但必须释放 HALF_OPEN 探测槽——
                    // isCallAllowed 在 HALF_OPEN 态占 activeHalfOpenProbes，不释放会累计卡死
                    // （H1；对照 LLM CircuitBreaker:102 doFinally→releaseProbe 兜底）
                    circuitRegistry.releaseProbe(key);
                }
                log.warn("MCP 工具 [{}] 调用失败，降级为 tool error", name, e);
                return McpToolResult.error("[tool call failed] " + McpErrors.rootMessage(e));
            }
        }
    }

    // ==================== McpResources（出口② 路径 C） ====================

    private final class McpResourcesImpl implements McpResources {

        @Override
        public McpResource read(URI uri, Subject subj) {
            Objects.requireNonNull(uri, "uri");
            if (subj == null || !subj.isAuthenticated()) {
                throw new ClientException(ClientErrorCode.FORBIDDEN, "MCP resource 读取：调用方主体未认证");
            }
            rejectBlockedScheme(uri);
            return executeRemote(uri.toString(), () -> client.readResource(
                    new McpSchema.ReadResourceRequest(uri.toString())),
                    r -> toResource(uri, r),
                    "resource 读取失败: " + uri);
        }
    }

    // ==================== McpPrompts（出口② 路径 C） ====================

    private final class McpPromptsImpl implements McpPrompts {

        @Override
        public McpPrompt get(String name, McpArgs args, Subject subj) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(args, "args");
            if (subj == null || !subj.isAuthenticated()) {
                throw new ClientException(ClientErrorCode.FORBIDDEN, "MCP prompt 取回：调用方主体未认证");
            }
            return executeRemote(name, () -> client.getPrompt(
                    new McpSchema.GetPromptRequest(name, args.asMap())),
                    r -> toPrompt(name, r),
                    "prompt 取回失败: " + name);
        }
    }

    // ==================== 远程调用统一模板（路径 C：熔断 + 计数 + 抛 RemoteException） ====================

    /**
     * 路径 C（resources/prompts）远程调用模板：熔断检查 → 调用 → 成功 recordSuccess / 失败（C 类）recordFailure
     * → 失败抛 {@link RemoteException}（业务出口，由消费侧处理，非 fail-soft）。
     */
    private <S, R> R executeRemote(String desc,
                                   RemoteCall<S> action,
                                   java.util.function.Function<S, R> mapper,
                                   String failureDetail) {
        String key = id.value();
        if (!circuitRegistry.isCallAllowed(key)) {
            throw new RemoteException(RemoteErrorCode.MCP_CIRCUIT_BREAKER_OPEN,
                    "MCP " + desc + " 被熔断: " + key);
        }
        try {
            S result = action.call();
            circuitRegistry.recordSuccess(key);
            initError = null;
            return mapper.apply(result);
        } catch (Exception e) {
            if (fallbackEligibility.isEligible(e)) {
                // C 类（远端故障/网络）→ 计熔断 + 抛 RemoteException（业务出口）
                circuitRegistry.recordFailure(key);
                throw new RemoteException(RemoteErrorCode.MCP_SERVER_UNREACHABLE, failureDetail, e);
            }
            // 非 eligible（NPE/ISE 编程错误）→ 非"远端不可达"，不误标 RemoteException（M1）也不计熔断；
            // 释放 HALF_OPEN 探测槽（H1），原异常经 ServiceException 让 GlobalExceptionHandler 归类
            circuitRegistry.releaseProbe(key);
            throw new ServiceException(ServiceErrorCode.INTERNAL_ERROR, "MCP " + desc + " 内部错误", e);
        }
    }

    @FunctionalInterface
    private interface RemoteCall<S> {
        S call() throws Exception;
    }

    // ==================== 映射 helper（starter 类型 → core 领域模型） ====================

    private static McpToolResult toToolResult(McpSchema.CallToolResult r) {
        String text = "";
        if (r.content() != null) {
            text = r.content().stream()
                    .filter(c -> c instanceof McpSchema.TextContent)
                    .map(c -> ((McpSchema.TextContent) c).text())
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("\n"));
        }
        boolean error = Boolean.TRUE.equals(r.isError()); // Boolean 可空 → TRUE 才算
        return new McpToolResult(text, error);
    }

    private static McpResource toResource(URI uri, McpSchema.ReadResourceResult r) {
        String text = null;
        String mime = null;
        if (r.contents() != null) {
            for (McpSchema.ResourceContents rc : r.contents()) {
                if (rc instanceof McpSchema.TextResourceContents trc) {
                    text = trc.text();
                    mime = trc.mimeType();
                    break;
                }
            }
        }
        return new McpResource(uri, text, mime);
    }

    private static McpPrompt toPrompt(String name, McpSchema.GetPromptResult r) {
        List<McpPrompt.PromptMessage> msgs = new ArrayList<>();
        if (r.messages() != null) {
            for (McpSchema.PromptMessage m : r.messages()) {
                String role = m.role() == null ? "user" : m.role().name().toLowerCase(Locale.ROOT);
                String content = (m.content() instanceof McpSchema.TextContent tc) ? tc.text() : "";
                msgs.add(new McpPrompt.PromptMessage(role, content));
            }
        }
        return new McpPrompt(name, r.description(), msgs);
    }

    private static void rejectBlockedScheme(URI uri) {
        String scheme = uri.getScheme();
        if (scheme != null && BLOCKED_URI_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST,
                    "MCP resource URI scheme 被禁: " + scheme);
        }
    }

    // ==================== 占位 / 资源管理（v4 B2 + 1.5）====================

    /** 是否持有真实 MCP client（false = 占位 server，无需 close） */
    boolean hasClient() {
        return client != null;
    }

    /** 安全关闭 client（try/catch，不抛） */
    void closeQuietly() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.debug("MCP client close ignored: {}", e.getMessage());
            }
        }
    }

    /** 占位 server 的 initError 暴露（用于 health indicator / registry log） */
    @Nullable
    String initError() {
        return initError;
    }

    // ==================== McpServerToolCallbacksAdapter（v4 B2）====================

    @Override
    public List<ToolCallback> toolCallbacks(McpServer server,
                                            McpToolFilter filter,
                                            McpToolNamePrefixGenerator prefixGen,
                                            ToolContextToMcpMetaConverter metaConverter) {
        if (client == null || initError != null) {
            return List.of();
        }
        McpConnectionInfo connInfo = McpConnectionInfo.builder()
                .clientCapabilities(client.getClientCapabilities())
                .clientInfo(client.getClientInfo())
                .initializeResult(client.getCurrentInitializationResult())
                .build();
        return client.listTools().tools().stream()
                .filter(tool -> filter.test(connInfo, tool))
                .<ToolCallback>map(tool -> SyncMcpToolCallback.builder()
                        .mcpClient(client)
                        .tool(tool)
                        .prefixedToolName(prefixGen.prefixedToolName(connInfo, tool))
                        .toolContextToMcpMetaConverter(metaConverter)
                        .build())
                .toList();
    }
}
