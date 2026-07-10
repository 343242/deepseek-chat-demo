package com.smart.rag.mcp.runtime;

import com.smart.rag.infrastructure.security.HostSafetyValidator;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.mcp.admin.entity.McpServerConfig;
import com.smart.rag.mcp.config.McpClientTransportConfiguration.McpClientTransportProperties;
import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Creates initialized MCP clients and closes partially initialized clients on failure. */
@Component
public class McpClientFactory {

    private static final Logger log = LoggerFactory.getLogger(McpClientFactory.class);

    private final HostSafetyValidator urlValidator;
    private final McpBearerTokenCodec tokenCodec;
    private final McpClientTransportProperties transportProps;
    private final McpClientBuilder clientBuilder;

    public McpClientFactory(HostSafetyValidator urlValidator,
                            McpBearerTokenCodec tokenCodec,
                            McpClientTransportProperties transportProps,
                            McpClientBuilder clientBuilder) {
        this.urlValidator = urlValidator;
        this.tokenCodec = tokenCodec;
        this.transportProps = transportProps;
        this.clientBuilder = clientBuilder;
    }

    /** Creates a client after URL validation and fail-closed bearer token decoding. */
    public McpSyncClient createClient(McpServerConfig config) {
        urlValidator.validate(config.getUrl());

        String bearerToken = tokenCodec.decode(config.getBearerTokenEncrypted());
        Duration timeout = parseTimeout(transportProps.getRequestTimeout(), Duration.ofSeconds(30));
        McpSyncClient client = clientBuilder.build(config.getUrl(), bearerToken, timeout);
        try {
            client.initialize();
            return client;
        } catch (RuntimeException e) {
            destroyClient(client);
            throw new RemoteException(RemoteErrorCode.MCP_SERVER_UNREACHABLE,
                    "MCP Server 初始化失败，请检查连接配置", e);
        }
    }

    /** 安全关闭 client（try/catch，不抛） */
    public void destroyClient(@Nullable McpSyncClient client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (Exception e) {
            log.warn("MCP client 关闭失败，errorType={}", e.getClass().getSimpleName());
        }
    }

    private static Duration parseTimeout(@Nullable String value, Duration fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Duration.parse(value.startsWith("PT") ? value : "PT" + value.toUpperCase());
        } catch (Exception e) {
            return fallback;
        }
    }
}
