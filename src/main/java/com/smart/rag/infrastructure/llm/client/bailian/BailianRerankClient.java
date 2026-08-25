package com.smart.rag.infrastructure.llm.client.bailian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.llm.*;
import com.smart.rag.infrastructure.llm.client.AbstractRerankClient;
import com.smart.rag.infrastructure.llm.client.HttpClientErrorHandler;
import com.smart.rag.infrastructure.llm.client.HttpClientFactory;
import com.smart.rag.infrastructure.llm.client.TimeoutParams;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;

/**
 * 百炼 Rerank 客户端 — OpenAI 兼容 API
 * <p>
 * 使用百炼 /reranks 端点（OpenAI 兼容格式）。
 * 不含线程池和重试——由 Resilient 装饰器层统一处理。
 * 阻塞路径走共享 OkHttp 传输（WS3 统一），超时经 {@link TimeoutParams} 配置化。
 */
public class BailianRerankClient extends AbstractRerankClient {

    private static final Logger log = LoggerFactory.getLogger(BailianRerankClient.class);
    private static final long READ_TIMEOUT_MS = 15_000;

    private final String apiKey;
    private final OkHttpClient okClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String endpoint;

    public BailianRerankClient(HttpClientFactory httpClientFactory, String baseUrl, String endpoint,
                               String apiKey, ModelCandidate candidate) {
        super(Objects.requireNonNull(candidate, "candidate must not be null"), candidate.provider());
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        Objects.requireNonNull(apiKey, "apiKey must not be null");
        this.baseUrl = baseUrl;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.objectMapper = new ObjectMapper();
        TimeoutParams timeouts = TimeoutParams.otherDefaults(READ_TIMEOUT_MS).mergeWithParams(candidate.params());
        this.okClient = httpClientFactory.sharedOkHttpClient(
            Duration.ofMillis(timeouts.connectTimeoutMs()),
            Duration.ofMillis(timeouts.readTimeoutMs()),
            Duration.ofMillis(timeouts.callTimeoutMs()));

        log.info("BailianRerankClient initialized: model={}, candidate={}",
            candidate.model(), candidate.id());
    }

    @Override
    public List<RerankResult> rerank(RerankRequest request) {
        if (request.documents() == null || request.documents().isEmpty()) {
            return List.of();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", candidate.model());
        body.put("query", request.query());
        body.put("documents", request.documents());
        body.put("top_n", request.documents().size());

        try {
            String json = HttpClientFactory.executePostJson(okClient, url(), apiKey,
                objectMapper.writeValueAsString(body), "Bailian Rerank");

            if (log.isDebugEnabled()) {
                log.debug("Bailian rerank response (model={}, docs={}): {}",
                    candidate.model(), request.documents().size(),
                    json.length() > 800 ? json.substring(0, 800) + "..." : json);
            }
            List<RerankResult> parsed = parseRerankResponse(objectMapper, json, request.documents());
            log.debug("Bailian rerank parsed: {} results from {} docs", parsed.size(), request.documents().size());
            return parsed;
        } catch (RemoteException e) {
            throw e;
        } catch (Exception e) {
            throw HttpClientErrorHandler.translate("Bailian Rerank", url(), e);
        }
    }

    private String url() {
        return baseUrl + (endpoint.startsWith("/") ? endpoint : "/" + endpoint);
    }

    @Override
    public void close() {
        // 共享 OkHttp 实例由 HttpClientFactory.closeAll() 统一管理，不在此关闭（WS3）
    }
}
