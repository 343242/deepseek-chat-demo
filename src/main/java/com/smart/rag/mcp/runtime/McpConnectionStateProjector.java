package com.smart.rag.mcp.runtime;

import com.smart.rag.mcp.admin.dto.McpConnectionStatus;
import com.smart.rag.mcp.admin.entity.McpServerConfig;
import org.springframework.stereotype.Component;

/**
 * The only PENDING/READY/DEGRADED/DISABLED derivation used by API and health (design §4.2).
 * <p>
 * Status is a read-time projection over DB facts plus a Registry runtime observation.
 * It is never independently persisted and cannot drift.
 *
 * <pre>
 * if (!enabled) return DISABLED;
 * if (errorCode != null) return DEGRADED;
 * if (runtime.live && runtime.circuitState != CLOSED) return DEGRADED;
 * if (runtime.live && desiredHash.equals(observedHash) && catalogSynced) return READY;
 * return PENDING;
 * </pre>
 */
@Component
public class McpConnectionStateProjector {

    /**
     * Runtime observation snapshot passed to the projector.
     */
    public record RuntimeObservation(boolean live, CircuitState circuitState) {
        public enum CircuitState { CLOSED, OPEN, HALF_OPEN }

        public static RuntimeObservation noLiveClient() {
            return new RuntimeObservation(false, CircuitState.CLOSED);
        }
    }

    public McpConnectionStatus project(McpServerConfig config, RuntimeObservation runtime) {
        if (Boolean.FALSE.equals(config.getEnabled())) {
            return McpConnectionStatus.DISABLED;
        }
        if (config.getErrorCode() != null) {
            return McpConnectionStatus.DEGRADED;
        }
        if (runtime.live() && runtime.circuitState() != RuntimeObservation.CircuitState.CLOSED) {
            return McpConnectionStatus.DEGRADED;
        }
        if (runtime.live()
                && config.getDesiredStateHash() != null
                && config.getDesiredStateHash().equals(config.getObservedStateHash())
                && Boolean.TRUE.equals(config.getCatalogSynced())) {
            return McpConnectionStatus.READY;
        }
        return McpConnectionStatus.PENDING;
    }

    /**
     * Project without a live client (e.g., during process restart before recovery).
     */
    public McpConnectionStatus project(McpServerConfig config) {
        return project(config, RuntimeObservation.noLiveClient());
    }
}
