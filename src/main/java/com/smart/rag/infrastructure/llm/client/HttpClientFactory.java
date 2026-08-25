package com.smart.rag.infrastructure.llm.client;

import jakarta.annotation.PreDestroy;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP 客户端工厂 — 封装 RestClient + HttpClient 的标准构建模板
 * <p>
 * 5 个 LLM 客户端（Generic/Bailian × Chat/Embedding/Rerank）共享相同的 HTTP 构建模式，
 * 仅有超时值差异。本工厂统一封装该模式，返回 {@link HttpHandles} 持有者。
 * <p>
 * <b>OkHttpClient 共享单例</b>（实例方法 {@link #sharedOkHttpClient}）：按
 * (connectTimeout, readTimeout) 缓存 OkHttpClient 实例。OkHttp 官方建议单例复用
 * （每实例持有独立连接池 + dispatcher 线程池），N 个 Generic Chat candidate 共享
 * 同一个 OkHttpClient 可避免连接池/dispatcher 资源放大 N 倍。生命周期由本工厂
 * {@link #closeAll()} 统一管理，使用方 {@code close()} 不应关闭共享实例。
 * <p>
 * <b>RestClient 共享单例</b>（实例方法 {@link #sharedRestClient}）：与 sharedOkHttpClient
 * 同型，按超时签名缓存。协议层（openai-compatible）阻塞路径使用——实例不绑
 * baseUrl/凭据，每请求绝对 URL + 显式 Authorization 头。
 * <p>
 * <b>buildRestClient 为何保持 static</b>：该方法无状态、返回 per-candidate 的
 * RestClient（每 candidate 独立 baseUrl/apiKey/timeout 必须独立实例）。static 调用
 * 减少注入成本，5 个客户端已有调用点无需改动。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>SRP — Abstract*Client 基类仅管 LLM 能力契约，HTTP 传输由本工厂负责</li>
 *   <li>组合优于继承 — 客户端持有 HttpHandles，而非继承传输实现</li>
 *   <li>OCP — 新增传输配置（代理、连接池）只改本工厂，不动基类层级</li>
 * </ul>
 */
@Component
public class HttpClientFactory {

    private static final Logger log = LoggerFactory.getLogger(HttpClientFactory.class);

    /** Shared OkHttp clients keyed by "connect_read" timeout signature */
    private final ConcurrentHashMap<String, OkHttpClient> sharedOkHttpClients = new ConcurrentHashMap<>();

    /** Shared (RestClient + underlying HttpClient) holders keyed by "connect_read" timeout signature */
    private final ConcurrentHashMap<String, HttpHandles> sharedRestClients = new ConcurrentHashMap<>();

    /**
     * 构建 RestClient + HttpClient 持有者，含标准 Bearer + JSON 头。
     * <p>
     * 调用方负责在 {@link AutoCloseable#close()} 中调用 {@link HttpHandles#close()}。
     * <p>
     * 保持 static：该方法无状态，且每个 candidate 的 baseUrl/apiKey/timeout 不同，
     * 必须返回独立实例，不存在共享语义。
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
     * 获取共享的 RestClient 实例（按超时参数缓存）— 协议层阻塞路径传输
     * （design llm-client-stateless §1 决策 3）。
     * <p>
     * 实例<b>不绑 baseUrl、不设默认 Authorization</b>（保留默认
     * {@code Content-Type: application/json} 头）：每请求绝对 URL + 显式
     * {@code Authorization: Bearer} 头（apiKey 缺省时省略该头）。相同
     * (connectTimeout, readTimeout) 组合返回同一实例；生命周期由本工厂
     * {@link #closeAll()} 统一管理，调用方不应关闭。
     *
     * @param connectTimeout 连接超时
     * @param readTimeout    读取超时
     * @return 共享 RestClient
     */
    public RestClient sharedRestClient(Duration connectTimeout, Duration readTimeout) {
        Objects.requireNonNull(connectTimeout, "connectTimeout must not be null");
        Objects.requireNonNull(readTimeout, "readTimeout must not be null");
        String key = connectTimeout.toMillis() + "_" + readTimeout.toMillis();
        HttpHandles handles = sharedRestClients.computeIfAbsent(key, k -> {
            log.info("Creating shared RestClient: connectTimeout={}ms, readTimeout={}ms",
                connectTimeout.toMillis(), readTimeout.toMillis());
            HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
            JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
            requestFactory.setReadTimeout(readTimeout);
            RestClient restClient = RestClient.builder()
                .defaultHeader("Content-Type", "application/json")
                .requestFactory(requestFactory)
                .build();
            return new HttpHandles(httpClient, restClient);
        });
        return handles.restClient();
    }

    /**
     * 获取共享的 OkHttpClient 实例（按超时参数缓存）。
     * <p>
     * 相同 (connectTimeout, readTimeout) 组合返回同一实例。共享实例由本工厂统一管理
     * 生命周期（{@link #closeAll()}），调用方不应在其 close() 中关闭。
     *
     * @param connectTimeout 连接超时
     * @param readTimeout    读取超时
     * @return 共享 OkHttpClient
     */
    public OkHttpClient sharedOkHttpClient(Duration connectTimeout, Duration readTimeout) {
        return sharedOkHttpClient(connectTimeout, readTimeout, Duration.ZERO);
    }

    /**
     * 获取共享的 OkHttpClient 实例（按超时参数缓存，WS2：缓存键扩展 connect_read_call）。
     * <p>
     * 相同 (connectTimeout, readTimeout, callTimeout) 组合返回同一实例；
     * {@code callTimeout = 0} 表示不限总时长（OkHttp callTimeout(0) 语义）。
     * 阻塞与流式按用途持不同签名实例——不得为省实例把两路合用（决策 12）：
     * 合用会把阻塞最坏时长放宽到 stream-call，或把流截断在阻塞 call-timeout。
     *
     * @param connectTimeout 连接超时
     * @param readTimeout    读取超时
     * @param callTimeout    调用总时长上限（0 = 不限）
     * @return 共享 OkHttpClient
     */
    public OkHttpClient sharedOkHttpClient(Duration connectTimeout, Duration readTimeout, Duration callTimeout) {
        Objects.requireNonNull(connectTimeout, "connectTimeout must not be null");
        Objects.requireNonNull(readTimeout, "readTimeout must not be null");
        Objects.requireNonNull(callTimeout, "callTimeout must not be null");
        String key = connectTimeout.toMillis() + "_" + readTimeout.toMillis() + "_" + callTimeout.toMillis();
        return sharedOkHttpClients.computeIfAbsent(key, k -> {
            log.info("Creating shared OkHttpClient: connectTimeout={}ms, readTimeout={}ms, callTimeout={}ms",
                connectTimeout.toMillis(), readTimeout.toMillis(), callTimeout.toMillis());
            return new OkHttpClient.Builder()
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout)
                .callTimeout(callTimeout)
                .build();
        });
    }

    /**
     * 关闭所有共享 HTTP 客户端实例（OkHttp + RestClient 底层 HttpClient）。由 Spring @PreDestroy 自动调用。
     */
    @PreDestroy
    public void closeAll() {
        int okCount = sharedOkHttpClients.size();
        if (okCount > 0) {
            sharedOkHttpClients.forEach((key, client) -> {
                try {
                    client.dispatcher().executorService().shutdown();
                    client.connectionPool().evictAll();
                } catch (Exception e) {
                    log.warn("Failed to close shared OkHttpClient (key={}): {}", key, e.getMessage());
                }
            });
            sharedOkHttpClients.clear();
            log.info("Closed {} shared OkHttpClient instances", okCount);
        }

        int restCount = sharedRestClients.size();
        if (restCount > 0) {
            sharedRestClients.forEach((key, handles) -> {
                try {
                    handles.close();
                } catch (Exception e) {
                    log.warn("Failed to close shared RestClient HttpClient (key={}): {}", key, e.getMessage());
                }
            });
            sharedRestClients.clear();
            log.info("Closed {} shared RestClient instances", restCount);
        }
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
