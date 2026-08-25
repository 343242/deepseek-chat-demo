package com.smart.rag.infrastructure.llm.config;

import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.security.HostSafetyValidator;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * 供应商连接配置
 * <p>
 * 对应 YAML 中 {@code providers.<id>} 下的连接信息。
 * <p>
 * 端点配置支持两种绑定形式：
 * <ul>
 *   <li>旧格式 {@code Map<String, String>} — 自动转换为 {@link EndpointConfig}</li>
 *   <li>新格式 {@link EndpointConfig} — 直接使用</li>
 * </ul>
 */
public record ProviderConfig(
    /** 基础 URL（如 https://dashscope.aliyuncs.com/compatible-mode/v1） */
    String url,

    /** API Key */
    String apiKey,

    /** 能力端点配置 */
    EndpointConfig endpoints
) {

    public ProviderConfig {
        if (endpoints == null) {
            endpoints = EndpointConfig.empty();
        }
    }

    /** 兼容旧格式构造 */
    public static ProviderConfig of(String url, String apiKey) {
        return new ProviderConfig(url, apiKey, EndpointConfig.empty());
    }

    /** 兼容旧格式构造 */
    public static ProviderConfig of(String url, String apiKey, Map<String, String> endpoints) {
        return new ProviderConfig(url, apiKey, EndpointConfig.of(endpoints));
    }

    /**
     * 供应商是否可用（url 非空；apiKey 可为空仅限服务器本地无鉴权端点，如同机部署 Ollama）。
     * <p>
     * 免 key 豁免经 {@link HostSafetyValidator#isLoopbackEndpoint} host 字面回环判定
     * （localhost / 127.0.0.0/8 字面 / [::1] 字面；纯字面解析不发 DNS，解析失败 fail-safe 拒绝）。
     * "本地"以应用服务器为视角：应用发出的回环请求只落在服务器自身；子串反例
     * （{@code x.localhost.evil.com}、path 含 localhost）不豁免 → 候选跳过。
     */
    public boolean isAvailable() {
        if (url == null || url.isBlank()) return false;
        if (apiKey == null || apiKey.isBlank()) {
            return HostSafetyValidator.isLoopbackEndpoint(url);
        }
        return true;
    }

    /** 按能力获取端点路径 */
    @Nullable
    public String getEndpoint(LlmCapability capability) {
        return endpoints.get(capability);
    }
}
