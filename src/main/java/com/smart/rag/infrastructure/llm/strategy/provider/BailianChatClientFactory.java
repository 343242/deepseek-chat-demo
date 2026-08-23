package com.smart.rag.infrastructure.llm.strategy.provider;

import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.ModelCandidate;
import com.smart.rag.infrastructure.llm.client.HttpClientFactory;
import com.smart.rag.infrastructure.llm.client.bailian.BailianChatClient;
import com.smart.rag.infrastructure.llm.client.generic.GenericChatClient;
import com.smart.rag.infrastructure.llm.strategy.ProviderClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Map;

/**
 * 百炼 Chat 客户端工厂 — dashscope-sdk-java（DashScope 原生协议）
 * <p>
 * 产出 {@link BailianChatClient}（SDK facade 按模型族双路由，见该类 Javadoc）。
 * <p>
 * <b>engagement 守卫</b>（设计 §4.3）：DB/BYOK 路径的 {@code provider_code='bailian'} 行可能
 * 携带自定义 baseUrl（私有网关/代理）——SDK 客户端以 DashScope 原生协议打该 URL 会静默打挂。
 * 仅当 baseUrl 属 DashScope 官方域（{@code dashscope.aliyuncs.com} /
 * {@code *.maas.aliyuncs.com}）或候选显式声明 {@code params.sdk-client: true} 时产出 SDK
 * 客户端，否则回落 {@link GenericChatClient}（OpenAI 兼容协议，行为与守卫加入前一致）。
 */
@Component
public class BailianChatClientFactory implements ProviderClientFactory {

    private static final Logger log = LoggerFactory.getLogger(BailianChatClientFactory.class);

    private final HttpClientFactory httpClientFactory;

    public BailianChatClientFactory(HttpClientFactory httpClientFactory) {
        this.httpClientFactory = httpClientFactory;
    }

    @Override
    public String providerId() {
        return "bailian";
    }

    @Override
    public LlmCapability capability() {
        return LlmCapability.CHAT;
    }

    @Override
    public CapabilityClient create(String baseUrl, String endpoint, String apiKey, ModelCandidate candidate) {
        if (!sdkEngaged(baseUrl, candidate.params())) {
            log.info("BailianChatClientFactory: baseUrl '{}' outside DashScope domains and no "
                + "params.sdk-client override, falling back to GenericChatClient for candidate '{}'",
                baseUrl, candidate.id());
            return new GenericChatClient(baseUrl, endpoint, apiKey, candidate, httpClientFactory);
        }
        return new BailianChatClient(sdkBaseUrl(baseUrl), apiKey, candidate);
    }

    /** 守卫：官方域（共享域名 / 业务空间专属域名）或显式 {@code params.sdk-client: true} */
    static boolean sdkEngaged(String baseUrl, Map<String, Object> params) {
        if (params != null && Boolean.TRUE.equals(params.get("sdk-client"))) {
            return true;
        }
        String host = hostOf(baseUrl);
        return host != null
            && (host.equals("dashscope.aliyuncs.com") || host.endsWith(".maas.aliyuncs.com"));
    }

    private static String hostOf(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return null;
        try {
            return URI.create(baseUrl.trim()).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * provider.url → SDK baseUrl：取域名部分（剥离兼容层路径），追加 {@code /api/v1} 前缀。
     * stable 的 {@code https://dashscope.aliyuncs.com/compatible-mode/v1} 取域名部分后重组，
     * 不携带兼容层路径（SDK 走原生路由）。委托 {@link DashScopeUrls}。
     */
    static String sdkBaseUrl(String providerUrl) {
        return DashScopeUrls.sdkBaseUrl(providerUrl);
    }
}
