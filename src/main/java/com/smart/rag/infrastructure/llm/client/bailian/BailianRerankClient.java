package com.smart.rag.infrastructure.llm.client.bailian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.llm.*;
import com.smart.rag.infrastructure.llm.client.AbstractRerankClient;
import com.smart.rag.infrastructure.llm.client.HttpClientErrorHandler;
import com.smart.rag.infrastructure.llm.client.HttpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.*;

/**
 * 百炼 Rerank 客户端 — OpenAI 兼容 API
 * <p>
 * 使用百炼 /reranks 端点（OpenAI 兼容格式）。
 * 不含线程池和重试——由 Resilient 装饰器层统一处理。
 */
public class BailianRerankClient extends AbstractRerankClient {

    private static final Logger log = LoggerFactory.getLogger(BailianRerankClient.class);
    private static final int CONNECT_TIMEOUT_SECONDS = 5;
    private static final int READ_TIMEOUT_SECONDS = 15;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final HttpClientFactory.HttpHandles http;
    private final String endpoint;

    public BailianRerankClient(String baseUrl, String endpoint, String apiKey, ModelCandidate candidate) {
        super(Objects.requireNonNull(candidate, "candidate must not be null"), candidate.provider());
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        Objects.requireNonNull(apiKey, "apiKey must not be null");
        this.endpoint = endpoint;
        this.objectMapper = new ObjectMapper();
        this.http = HttpClientFactory.buildRestClient(baseUrl, apiKey,
            Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS), Duration.ofSeconds(READ_TIMEOUT_SECONDS));
        this.restClient = http.restClient();

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
            String json = restClient.post()
                .uri(endpoint)
                .body(body)
                .retrieve()
                .body(String.class);

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
            throw HttpClientErrorHandler.translate("Bailian Rerank", endpoint, e);
        }
    }

    @Override
    public void close() {
        http.close();
    }
}
