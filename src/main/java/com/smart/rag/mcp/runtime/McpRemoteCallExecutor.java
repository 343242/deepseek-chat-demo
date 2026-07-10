package com.smart.rag.mcp.runtime;

import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.infrastructure.fallback.FallbackEligibility;
import com.smart.rag.mcp.core.ServerId;

import java.util.function.Function;

/** Applies circuit-breaker accounting and exception taxonomy to remote MCP calls. */
final class McpRemoteCallExecutor {

    private final ServerId serverId;
    private final McpCircuitBreakerRegistry circuitRegistry;
    private final FallbackEligibility fallbackEligibility;

    McpRemoteCallExecutor(ServerId serverId,
                          McpCircuitBreakerRegistry circuitRegistry,
                          FallbackEligibility fallbackEligibility) {
        this.serverId = serverId;
        this.circuitRegistry = circuitRegistry;
        this.fallbackEligibility = fallbackEligibility;
    }

    <S, R> R execute(Operation<S, R> operation) {
        String key = serverId.value();
        if (!circuitRegistry.isCallAllowed(key)) {
            throw new RemoteException(RemoteErrorCode.MCP_CIRCUIT_BREAKER_OPEN,
                    "MCP Server 当前熔断，请稍后重试");
        }
        try {
            S result = operation.action().call();
            circuitRegistry.recordSuccess(key);
            operation.onSuccess().run();
            return operation.mapper().apply(result);
        } catch (RuntimeException e) {
            if (fallbackEligibility.isEligible(e)) {
                circuitRegistry.recordFailure(key);
                throw new RemoteException(RemoteErrorCode.MCP_SERVER_UNREACHABLE,
                        operation.failureMessage(), e);
            }
            circuitRegistry.releaseProbe(key);
            throw new ServiceException(ServiceErrorCode.INTERNAL_ERROR,
                    "MCP " + operation.name() + " 内部处理失败", e);
        }
    }

    record Operation<S, R>(
            String name,
            RemoteCall<S> action,
            Function<S, R> mapper,
            String failureMessage,
            Runnable onSuccess
    ) {}

    @FunctionalInterface
    interface RemoteCall<S> {
        S call();
    }
}
