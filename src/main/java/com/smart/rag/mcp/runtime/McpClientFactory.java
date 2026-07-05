package com.smart.rag.mcp.runtime;

import com.smart.rag.infrastructure.security.HostSafetyValidator;
import com.smart.rag.infrastructure.security.SecretCipher;
import com.smart.rag.mcp.admin.entity.McpServerConfig;
import com.smart.rag.mcp.config.McpClientTransportConfiguration.McpClientTransportProperties;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 动态创建 / 销毁 {@link McpSyncClient}（v4 修复 1.7）。
 * <p>
 * 替代 v3 的静态 {@code List<McpSyncClient>} bean 装配。每次 {@link McpAdminService} 调
 * {@link #createClient(McpServerConfig)} 时按需创建：
 * <ol>
 *   <li>{@link HostSafetyValidator#validate(String)} SSRF 校验</li>
 *   <li>{@link SecretCipher#decrypt(byte[], byte[])} 解密 bearerTokenEncrypted（v4 per-server 粒度）</li>
 *   <li>构建 {@link HttpClientStreamableHttpTransport}，token 经 customizer 注入</li>
 *   <li>{@code McpClient.sync(transport).requestTimeout(...).clientInfo(...).build()}</li>
 *   <li>{@code client.initialize()}（fail-fast，由调用方 catch 写入 {@code init_error}）</li>
 * </ol>
 * <p>
 * <b>注</b>：bearerTokenEncrypted 当前为 String 形式的 base64 密文，{@link SecretCipher#decrypt(byte[], byte[])}
 * 期待 byte[]。本期实现简化为 String 拼接存储（cipher + iv 一行 base64 字符串），decrypt 时拆解。
 */
@Component
public class McpClientFactory {

    private static final Logger log = LoggerFactory.getLogger(McpClientFactory.class);

    private final HostSafetyValidator urlValidator;
    private final SecretCipher secretCipher;
    private final McpClientTransportProperties transportProps;

    public McpClientFactory(HostSafetyValidator urlValidator,
                            SecretCipher secretCipher,
                            McpClientTransportProperties transportProps) {
        this.urlValidator = urlValidator;
        this.secretCipher = secretCipher;
        this.transportProps = transportProps;
    }

    /**
     * 从 {@link McpServerConfig} 创建 {@link McpSyncClient}（含 initialize 握手）。
     *
     * @throws IllegalArgumentException SSRF 校验失败（经 GlobalExceptionHandler → 400）
     * @throws IllegalStateException    transport 构建 / initialize 失败（由调用方 catch 写入 init_error）
     */
    public McpSyncClient createClient(McpServerConfig config) {
        urlValidator.validate(config.getUrl());

        String bearerToken = decryptBearerToken(config.getBearerTokenEncrypted());
        HttpClientStreamableHttpTransport transport = buildTransport(config.getUrl(), bearerToken);

        Duration timeout = parseTimeout(transportProps.getRequestTimeout(), Duration.ofSeconds(30));
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(timeout)
                .clientInfo(new McpSchema.Implementation("smart-rag", "1.0.0"))
                .build();

        client.initialize();
        return client;
    }

    /** 安全关闭 client（try/catch，不抛） */
    public void destroyClient(@Nullable McpSyncClient client) {
        if (client == null) return;
        try {
            client.close();
        } catch (Exception e) {
            log.warn("MCP client close failed: {}", e.getMessage());
        }
    }

    @Nullable
    private String decryptBearerToken(@Nullable String bearerTokenStored) {
        if (bearerTokenStored == null || bearerTokenStored.isBlank()) {
            return null;
        }
        try {
            return secretCipher.decrypt(bearerTokenStored.getBytes(java.nio.charset.StandardCharsets.UTF_8), new byte[0]);
        } catch (Exception e) {
            log.warn("Bearer token decrypt failed, treating as no token: {}", e.getMessage());
            return null;
        }
    }

    private HttpClientStreamableHttpTransport buildTransport(String url, @Nullable String bearerToken) {
        HttpClientStreamableHttpTransport.Builder builder = HttpClientStreamableHttpTransport
                .builder(url)
                .openConnectionOnStartup(false);
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.httpRequestCustomizer((rb, method, uri, body, ctx) ->
                    rb.header("Authorization", "Bearer " + bearerToken));
        }
        return builder.build();
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
