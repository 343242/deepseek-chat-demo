package com.smart.rag.infrastructure.llm;

import org.springframework.lang.Nullable;

import java.util.Objects;

/**
 * 启动期静态解析的协议端点 — 凭据是端点数据，不是对象身份（design llm-client-stateless §1 决策 4）。
 * <p>
 * 在 {@code GenericChatClient} 两个构造点（ChatCapabilityStrategy / BailianChatClientFactory）
 * 由 strategy 已解析的 (baseUrl, apiKey, endpoint) 三元组一次性构造并闭包进薄壳。
 * <p>
 * {@code apiKey} 可缺省：null/blank = 无鉴权端点（服务器本地 Ollama 同机部署场景；
 * 免 key 豁免门卫见 {@code ProviderConfig.isAvailable()}）。
 *
 * @param baseUrl  基础 URL（非空）
 * @param apiKey   Bearer 凭据，可缺省
 * @param endpoint 能力端点路径（非空；未配置 → 构造失败 → 候选跳过，fail-fast）
 */
public record ResolvedEndpoint(String baseUrl, @Nullable String apiKey, String endpoint) {

    public ResolvedEndpoint {
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        Objects.requireNonNull(endpoint, "endpoint must not be null");
    }

    /** apiKey 非 null 且非 blank（缺省 = 无鉴权端点，协议层不发送 Authorization 头） */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** toString 脱敏 apiKey（AC6：凭据不出现在任何日志输出） */
    @Override
    public String toString() {
        return "ResolvedEndpoint[baseUrl=" + baseUrl
            + ", apiKey=" + (hasApiKey() ? "****" : "null")
            + ", endpoint=" + endpoint + "]";
    }
}
