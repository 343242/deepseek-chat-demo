package com.smart.rag.infrastructure.llm.client;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;

/**
 * HTTP 客户端工厂 — 封装 RestClient + HttpClient 的标准构建模板
 * <p>
 * 5 个 LLM 客户端（Generic/Bailian × Chat/Embedding/Rerank）共享相同的 HTTP 构建模式，
 * 仅有超时值差异。本工厂统一封装该模式，返回 {@link HttpHandles} 持有者。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>SRP — Abstract*Client 基类仅管 LLM 能力契约，HTTP 传输由本工厂负责</li>
 *   <li>组合优于继承 — 客户端持有 HttpHandles，而非继承传输实现</li>
 *   <li>OCP — 新增传输配置（代理、连接池）只改本工厂，不动基类层级</li>
 * </ul>
 */
public final class HttpClientFactory {

    private HttpClientFactory() {}

    /**
     * 构建 RestClient + HttpClient 持有者，含标准 Bearer + JSON 头。
     * <p>
     * 调用方负责在 {@link AutoCloseable#close()} 中调用 {@link HttpHandles#close()}。
     *
     * @param baseUrl        基础 URL
     * @param apiKey         Bearer token
     * @param connectTimeout 连接超时
     * @param readTimeout    读取超时
     * @return 持有 HttpClient + RestClient 的不可变记录，AutoCloseable
     */
    public static HttpHandles buildRestClient(String baseUrl, String apiKey,
                                               Duration connectTimeout, Duration readTimeout) {
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        Objects.requireNonNull(apiKey, "apiKey must not be null");
        Objects.requireNonNull(connectTimeout, "connectTimeout must not be null");
        Objects.requireNonNull(readTimeout, "readTimeout must not be null");

        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        RestClient restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .defaultHeader("Content-Type", "application/json")
            .requestFactory(requestFactory)
            .build();
        return new HttpHandles(httpClient, restClient);
    }

    /**
     * HTTP 资源持有者 — 持有 RestClient 及其底层 HttpClient，统一 close 生命周期。
     */
    public record HttpHandles(HttpClient httpClient, RestClient restClient) implements AutoCloseable {
        @Override
        public void close() {
            httpClient.close();
        }
    }
}
