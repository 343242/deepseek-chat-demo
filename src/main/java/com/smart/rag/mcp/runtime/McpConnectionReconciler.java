package com.smart.rag.mcp.runtime;

import com.smart.rag.mcp.admin.entity.McpServerConfig;
import com.smart.rag.mcp.admin.mapper.McpServerConfigMapper;
import com.smart.rag.mcp.admin.mapper.McpToolConfigMapper;
import com.smart.rag.mcp.admin.entity.McpToolConfig;
import com.smart.rag.mcp.admin.service.McpServerRuntime;
import com.smart.rag.mcp.mcpclient.McpToolUtils;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One captured-hash connection/catalog attempt (design §6).
 * <p>
 * Each invocation does exactly one connect attempt outside transaction/guard,
 * then conditionally commits observation under the mutation guard.
 * No internal retry loop — the scheduler owns retry scheduling.
 */
@Component
public class McpConnectionReconciler {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionReconciler.class);

    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(5);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);
    private static final int PERMANENT_FAILURE_THRESHOLD = 10;

    private final McpServerConfigMapper serverMapper;
    private final McpToolConfigMapper toolMapper;
    private final McpServerRuntime runtime;
    private final McpClientFactory clientFactory;
    private final McpDesiredStateHasher hasher;
    private final TransactionTemplate txTemplate;

    public McpConnectionReconciler(McpServerConfigMapper serverMapper,
                                   McpToolConfigMapper toolMapper,
                                   McpServerRuntime runtime,
                                   McpClientFactory clientFactory,
                                   McpDesiredStateHasher hasher,
                                   TransactionTemplate txTemplate) {
        this.serverMapper = serverMapper;
        this.toolMapper = toolMapper;
        this.runtime = runtime;
        this.clientFactory = clientFactory;
        this.hasher = hasher;
        this.txTemplate = txTemplate;
    }

    /**
     * One connection attempt for the given server ID.
     */
    public void reconcile(String serverId) {
        McpServerConfig config = serverMapper.selectByServerId(serverId);
        if (config == null || Boolean.FALSE.equals(config.getEnabled())) {
            return;
        }

        String capturedHash = config.getDesiredStateHash();
        if (capturedHash == null) {
            return;
        }

        // P2-10: Catalog-only path — live client exists with matching observed hash, just sync catalog
        boolean catalogOnly = config.getObservedStateHash() != null
                && config.getObservedStateHash().equals(capturedHash)
                && Boolean.FALSE.equals(config.getCatalogSynced())
                && runtime.find(serverId).isPresent();

        if (catalogOnly) {
            try {
                var existingClient = ((com.smart.rag.mcp.runtime.McpServerImpl)
                        runtime.find(serverId).get()).getClient();
                if (existingClient != null) {
                    syncCatalog(config, capturedHash, existingClient);
                    return;
                }
            } catch (Exception e) {
                log.debug("Catalog-only path failed, falling through to full reconnect: {}", e.getMessage());
            }
            // Fall through to full reconnect if catalog-only fails
        }

        // Full reconnect path
        McpSyncClient client;
        try {
            client = runtime.connect(config);
        } catch (RuntimeException e) {
            persistFailure(config, capturedHash, McpErrors.safeCode(e), McpErrors.safeSummary(e));
            return;
        }

        // Conditional observed success under guard
        String remoteName = extractRemoteName(client);
        String observedHash = capturedHash;

        int affected = runtime.withMutationGuard(() ->
                serverMapper.updateObservedSuccess(
                        config.getId(), capturedHash, observedHash, remoteName));

        if (affected == 0) {
            clientFactory.destroyClient(client);
            return;
        }

        // Publish the client into registry
        try {
            runtime.add(config, client);
        } catch (RuntimeException e) {
            log.warn("Registry publish failed, cleaning up: serverId={}", serverId, e);
            runtime.removeIfSame(serverId, null);
            clientFactory.destroyClient(client);
            return;
        }

        // Catalog sync
        try {
            syncCatalog(config, capturedHash, client);
        } catch (RuntimeException e) {
            log.warn("Catalog sync failed for serverId={}: {}", serverId, e.getMessage());
            int failures = config.getConsecutiveFailures() != null ? config.getConsecutiveFailures() : 0;
            serverMapper.updateObservedFailure(
                    config.getId(), capturedHash,
                    McpErrors.CATALOG_SYNC_FAILED_CODE,
                    McpErrors.CATALOG_SYNC_FAILED_MESSAGE,
                    failures,
                    OffsetDateTime.now().plus(Duration.ofSeconds(30)));
        }
    }

    private void syncCatalog(McpServerConfig config, String capturedHash, McpSyncClient client) {
        List<McpSchema.Tool> remoteTools;
        try {
            remoteTools = client.listTools().tools();
        } catch (RuntimeException e) {
            throw new RemoteException(RemoteErrorCode.MCP_SERVER_UNREACHABLE,
                    "MCP Server 工具列表获取失败", e);
        }

        if (remoteTools == null || remoteTools.isEmpty()) {
            txTemplate.executeWithoutResult(status ->
                    serverMapper.markCatalogSynced(config.getId(), capturedHash));
            return;
        }

        String serverId = config.getServerId();
        List<McpToolConfig> toolConfigs = remoteTools.stream()
                .map(tool -> toToolConfig(serverId, tool))
                .toList();

        Set<String> seenNames = remoteTools.stream()
                .map(McpSchema.Tool::name)
                .collect(Collectors.toSet());

        // Transactional: upsert + mark absent + mark synced (all-or-nothing)
        txTemplate.executeWithoutResult(status -> {
            toolMapper.batchUpsert(toolConfigs);
            toolMapper.markAbsentExcept(serverId, List.copyOf(seenNames));
            serverMapper.markCatalogSynced(config.getId(), capturedHash);
        });
    }

    private McpToolConfig toToolConfig(String serverId, McpSchema.Tool tool) {
        McpToolConfig tc = new McpToolConfig();
        tc.setServerId(serverId);
        tc.setToolName(tool.name());
        tc.setPrefixedToolName(McpToolUtils.prefixedToolName(serverId, tool.name()));
        tc.setDescription(tool.description());
        tc.setEnabled(false);
        tc.setIntent("GENERAL_TOOL");
        tc.setRisk("low");
        tc.setPresent(true);
        tc.setInputSchema(toJsonString(tool.inputSchema()));
        return tc;
    }

    private void persistFailure(McpServerConfig config, String capturedHash,
                                 String errorCode, String errorMessage) {
        int failures = (config.getConsecutiveFailures() != null
                ? config.getConsecutiveFailures() : 0) + 1;
        OffsetDateTime nextRetry = failures >= PERMANENT_FAILURE_THRESHOLD
                ? null  // stop retrying permanently
                : OffsetDateTime.now().plus(calculateBackoff(failures));

        serverMapper.updateObservedFailure(
                config.getId(), capturedHash, errorCode, errorMessage,
                failures, nextRetry);
    }

    private static Duration calculateBackoff(int failures) {
        long backoffSeconds = INITIAL_BACKOFF.toSeconds() * (1L << Math.min(failures - 1, 6));
        backoffSeconds = Math.min(backoffSeconds, MAX_BACKOFF.toSeconds());
        // Add jitter (0-25%)
        long jitter = (long) (backoffSeconds * 0.25 * Math.random());
        return Duration.ofSeconds(backoffSeconds + jitter);
    }

    @Nullable
    private static String extractRemoteName(McpSyncClient client) {
        try {
            McpSchema.InitializeResult result = client.getCurrentInitializationResult();
            return result != null && result.serverInfo() != null
                    ? result.serverInfo().name() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static String toJsonString(Object schema) {
        if (schema == null) return "{}";
        if (schema instanceof String s) return s;
        try {
            return OBJECT_MAPPER.writeValueAsString(schema);
        } catch (Exception e) {
            return "{}";
        }
    }
}
