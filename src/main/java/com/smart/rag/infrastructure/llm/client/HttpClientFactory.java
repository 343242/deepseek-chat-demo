package com.smart.rag.infrastructure.llm.client;

import jakarta.annotation.PreDestroy;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP 客户端工厂 — OkHttp 统一传输栈（design llm-resilience-optimization WS3，P6）
 * <p>
 * 全部自研客户端（阻塞 + 流式）走 {@link #sharedOkHttpClient} 共享池，
 * 按 (connect, read, call) 超时签名缓存。OkHttp 官方建议单例复用（每实例持有独立
 * 连接池 + dispatcher 线程池），共享避免资源随候选数放大。生命周期由本工厂
 * {@link #closeAll()} 统一管理，使用方 {@code close()} 不应关闭共享实例。
 * <p>
 * <b>followRedirects(false)</b>：对齐被替换的 JDK HttpClient 默认语义（不跟随重定向），
 * 防迁移引入行为差异（决策 11）。
 * <p>
 * 共享实例不绑 baseUrl/凭据：每请求绝对 URL + 显式 {@code Authorization: Bearer} 头
 * （apiKey 缺省时省略该头，见 {@link ResolvedEndpoint} 无鉴权端点支持）。
 */
@Component
public class HttpClientFactory {

    private static final Logger log = LoggerFactory.getLogger(HttpClientFactory.class);
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    /** Shared OkHttp clients keyed by "connect_read_call" timeout signature */
    private final ConcurrentHashMap<String, OkHttpClient> sharedOkHttpClients = new ConcurrentHashMap<>();

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
                .followRedirects(false)
                .build();
        });
    }

    /**
     * OkHttp 同步 POST JSON 统一模板（WS3）：阻塞路径标准出口。
     * <ul>
     *   <li>非 2xx → {@code HttpClientErrorHandler.translateStatus}（读错误体 4KB + Retry-After）</li>
     *   <li>IOException → {@code HttpClientErrorHandler.translate}（LLM_TRANSIENT_ERROR）</li>
     *   <li>2xx → 响应体字符串（调用方自行 Jackson 解析）</li>
     * </ul>
     *
     * @param client    共享 OkHttpClient（超时已由签名决定）
     * @param url       绝对 URL
     * @param apiKey    Bearer token；null/blank 时不发送 Authorization 头
     * @param jsonBody  请求体 JSON 字符串
     * @param operation 操作描述（异常消息用，如 "Embedding"）
     * @return 响应体字符串
     */
    public static String executePostJson(OkHttpClient client, String url, @Nullable String apiKey,
            String jsonBody, String operation) {
        Request.Builder builder = new Request.Builder()
            .url(url)
            .post(RequestBody.create(jsonBody, JSON_MEDIA));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        Call call = client.newCall(builder.build());
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                String body = "";
                try (ResponseBody peek = response.peekBody(4096)) {
                    if (peek != null) body = peek.string();
                }
                throw HttpClientErrorHandler.translateStatus(operation, url,
                    response.code(), body, response.header("Retry-After"));
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                return "";
            }
            return responseBody.string();
        } catch (IOException e) {
            throw HttpClientErrorHandler.translate(operation, url, e);
        }
    }

    /**
     * 关闭所有共享 HTTP 客户端实例。由 Spring @PreDestroy 自动调用。
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
    }
}
