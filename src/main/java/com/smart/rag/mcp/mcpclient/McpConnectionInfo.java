package com.smart.rag.mcp.mcpclient;

import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.lang.Nullable;

/**
 * MCP 连接元信息（client + server 握手结果），供 {@link McpToolFilter} 和
 * {@link McpToolNamePrefixGenerator} 做过滤/前缀决策。
 * <p>
 * 参照 Spring AI 2.0.0 {@code McpConnectionInfo}（record + builder）。
 *
 * @param clientCapabilities MCP client 能力声明
 * @param clientInfo        MCP client 身份（name/version）
 * @param initializeResult  MCP server 握手结果（null=未初始化/握手失败）
 * @author Ilayaperumal Gopinathan, Christian Tzolov（原 Spring AI）
 */
public record McpConnectionInfo(
        McpSchema.ClientCapabilities clientCapabilities,
        McpSchema.Implementation clientInfo,
        @Nullable McpSchema.InitializeResult initializeResult) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private @Nullable McpSchema.ClientCapabilities clientCapabilities;
        private @Nullable McpSchema.Implementation clientInfo;
        private @Nullable McpSchema.InitializeResult initializeResult;

        private Builder() {
        }

        public Builder clientCapabilities(McpSchema.ClientCapabilities clientCapabilities) {
            this.clientCapabilities = clientCapabilities;
            return this;
        }

        public Builder clientInfo(McpSchema.Implementation clientInfo) {
            this.clientInfo = clientInfo;
            return this;
        }

        public Builder initializeResult(McpSchema.InitializeResult initializeResult) {
            this.initializeResult = initializeResult;
            return this;
        }

        public McpConnectionInfo build() {
            return new McpConnectionInfo(
                    this.clientCapabilities != null ? this.clientCapabilities
                            : McpSchema.ClientCapabilities.builder().build(),
                    this.clientInfo != null ? this.clientInfo
                            : new McpSchema.Implementation("unknown", "0.0.0"),
                    this.initializeResult);
        }
    }
}
