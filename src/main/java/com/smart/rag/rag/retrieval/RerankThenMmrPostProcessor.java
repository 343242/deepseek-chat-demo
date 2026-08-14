package com.smart.rag.rag.retrieval;

import com.smart.rag.infrastructure.concurrent.context.ContextCarrier;
import com.smart.rag.infrastructure.concurrent.context.ContextRestorer;
import com.smart.rag.infrastructure.concurrent.context.ContextSnapshot;
import com.smart.rag.infrastructure.concurrent.context.MdcContextCarrier;
import com.smart.rag.infrastructure.concurrent.context.SecurityContextCarrier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 复合后处理器：Rerank（LLM 精排）⊥ MMR distance 预取（DB）并行，MMR 贪心在两者就绪后串行。
 * <p>
 * Spring AI {@code RetrievalAugmentationAdvisor} 是 final，postProcessor 链为框架硬编码顺序 iterator，
 * 无法子类化覆盖编排 → Rerank 与 MMR 的并行只能封装进单个复合处理器（design §2）。
 * <p>
 * 并行形态（CompletableFuture，非 ScopedTasks —— 2 fork+1 join 场景 ScopedTasks 过度设计，且引入
 * scope 超时撕穿降级契约 B1 / 异常被 CollectAllPolicy 静默吞 B2 两个陷阱，design §4.2）：
 * <pre>
 *   rerank:   supplyAsync(ragPostProcessExecutor).exceptionally(透传)  // 虚拟线程，LLM IO
 *   distance: 主线程同步 try-catch→null                                // 平台线程，DB IO（无 pinning 风险）
 *   join rerank → selectByMmr(reranked, distance)                      // distance==null 走 relevance-only
 * </pre>
 * 耗时 ≈ max(rerank, distance) ≈ rerank，distance 的 DB 等待被吸收。
 * <p>
 * 降级契约矩阵（design §4.3）：
 * <ul>
 *   <li>rerank API 失败/超时 → .exceptionally 透传原文档；MMR 用 rrfScore fallback（无 ScopeTimeoutException，B1 消除）</li>
 *   <li>rerank 返回空 → rerankOnly 透传原文档</li>
 *   <li>distance 失败 → fetchDistanceMatrix 返回 null → selectByMmr 走 relevance-only</li>
 *   <li>两者皆失败 → 透传 + relevance-only，不抛出（B2：无静默吞，exceptionally 显式降级）</li>
 *   <li>blank query → rerankOnly 透传</li>
 * </ul>
 * <p>
 * B3 无状态：rerankOnly/fetchDistanceMatrix/selectByMmr 均委托给持有的两个无状态处理器实例，
 * 无中间实例字段，{@code cachedPostProcessors} 跨请求共享安全。
 */
public class RerankThenMmrPostProcessor implements DocumentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(RerankThenMmrPostProcessor.class);

    private final RerankDocumentPostProcessor rerankProcessor;
    private final MmrDocumentPostProcessor mmrProcessor;
    private final ExecutorService ragPostProcessExecutor;

    /**
     * ragPostProcessExecutor（{@code RagSearchExecutorConfig} 的
     * {@code Executors.newVirtualThreadPerTaskExecutor()}）本身不做上下文传播，
     * 因此在提交前于调用线程捕获 MDC + SecurityContext，在异步任务内恢复——
     * 与 ScopedTasks 的 {@code inheritMdc}/{@code inheritSecurityContext} 默认载体一致
     * （见 DefaultScopedTasks.carriers）。RequestContext 为容器 filter 作用域，此处不传播。
     */
    private static final List<ContextCarrier<?>> CONTEXT_CARRIERS =
            List.of(new MdcContextCarrier(), new SecurityContextCarrier());

    public RerankThenMmrPostProcessor(RerankDocumentPostProcessor rerankProcessor,
                                      MmrDocumentPostProcessor mmrProcessor,
                                      ExecutorService ragPostProcessExecutor) {
        this.rerankProcessor = rerankProcessor;
        this.mmrProcessor = mmrProcessor;
        this.ragPostProcessExecutor = ragPostProcessExecutor;
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        // rerank 异步到虚拟线程 bean（LLM IO 百 ms 级）；.exceptionally 降级透传（B2：无静默吞）
        ContextSnapshot contextSnapshot = ContextSnapshot.capture(CONTEXT_CARRIERS);
        CompletableFuture<List<Document>> rerankFut = CompletableFuture
                .supplyAsync(() -> {
                    try (ContextRestorer ignored = contextSnapshot.restore()) {
                        return rerankProcessor.rerankOnly(query, documents);
                    }
                }, ragPostProcessExecutor)
                .exceptionally(ex -> {
                    log.warn("Rerank async failed, passthrough original docs", ex);
                    return documents;
                });

        // distance 主线程同步预取（DB IO 十 ms 级，与 rerank 重叠）；失败→null→relevance-only。
        // 跑在调用线程（平台线程）→ 无 JDBC/MyBatis/HikariCP 虚拟线程 pinning 风险（design §7）
        Map<String, Double> distance = mmrProcessor.fetchDistanceMatrix(documents);

        // 等 rerank（此时 distance 通常已算完，重叠收益 = distance 的 DB 等待被吸收）
        List<Document> reranked = rerankFut.join();

        // MMR 贪心：reranked 上用预取距离矩阵 + rerankScore；distance==null 走 relevance-only
        return mmrProcessor.selectByMmr(query, reranked, distance);
    }
}
