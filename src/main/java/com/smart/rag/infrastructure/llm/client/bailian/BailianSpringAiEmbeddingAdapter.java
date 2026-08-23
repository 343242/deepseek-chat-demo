package com.smart.rag.infrastructure.llm.client.bailian;

import com.smart.rag.infrastructure.llm.EmbeddingCapable;
import com.smart.rag.infrastructure.llm.EmbeddingType;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI {@link EmbeddingModel} 适配器 — 桥接 {@link EmbeddingCapable}（SPI 层）
 * 与 Spring AI 框架类型。
 * <p>
 * <b>设计原则</b>（详见 spec §5.5 Adapter 模式）：
 * <ul>
 *   <li>SRP — Spring AI 适配代码集中在此，不污染 SPI 客户端</li>
 *   <li>ISP — SPI 客户端（如 BailianEmbeddingClient）仅实现 {@code EmbeddingCapable}，
 *       Spring AI 调用方（PgVectorStore、AnswerRelevanceScorer）通过本适配器获取 {@code EmbeddingModel} 视图</li>
 *   <li>LSP — 本适配器是独立的 {@code EmbeddingModel} 实现，所有方法委托给底层 SPI 客户端</li>
 * </ul>
 * <p>
 * <b>生命周期</b>：本适配器不持有任何资源（HTTP 连接、线程池等由底层 SPI 客户端管理）。资源释放由
 * {@link com.smart.rag.infrastructure.llm.registry.LlmClientRegistry} 统一管理。
 * <p>
 * <b>命名映射</b>：SPI 接口 {@code dimension()}（单数）→ Spring AI {@code dimensions()}（复数）。
 * 本适配器在方法签名上对齐 Spring AI，内部委托给 SPI 的 {@code dimension()}。
 * <p>
 * 构造参数为 {@link EmbeddingCapable}（设计 §4.4 泛化：底层客户端替换对适配器透明）
 * 或通过 {@link com.smart.rag.infrastructure.llm.config.LlmAutoConfiguration#embeddingModel} 自动装配。
 */
public class BailianSpringAiEmbeddingAdapter implements EmbeddingModel {

    private final EmbeddingCapable delegate;

    public BailianSpringAiEmbeddingAdapter(EmbeddingCapable delegate) {
        this.delegate = delegate;
    }

    /** 返回被适配的底层 SPI 客户端（供 {@code LlmAutoConfiguration} 等做类型检测） */
    public EmbeddingCapable delegate() {
        return delegate;
    }

    /**
     * 嵌入单个 {@link Document}（按 DOCUMENT 类型编码）。
     * <p>
     * 空内容返回零向量（与原 {@code BailianEmbeddingClient} 行为一致）。
     */
    @Override
    public float @NonNull [] embed(@NonNull Document document) {
        String content = document.getText();
        if (content == null || content.isBlank()) {
            // Spring AI @NonNull return contract — return zero vector rather than null
            return new float[delegate.dimension()];
        }
        return delegate.embed(content, EmbeddingType.DOCUMENT);
    }

    /**
     * 嵌入单条文本（按 QUERY 类型编码）。
     * <p>
     * 空白字符串返回零向量。
     */
    @Override
    public float @NonNull [] embed(@NonNull String text) {
        if (text == null || text.isBlank()) {
            return new float[delegate.dimension()];
        }
        return delegate.embed(text, EmbeddingType.QUERY);
    }

    /**
     * 批量嵌入 {@link Document}（按 DOCUMENT 类型编码）。
     * <p>
     * <b>chunk 写库主路径</b>：Spring AI {@code PgVectorStore.doAdd} 经
     * {@code embed(documents, options, batchingStrategy)} 批量向量化入库。
     * 接口默认实现会把文档文本转成 {@link EmbeddingRequest} 走 {@link #call()}（QUERY 编码），
     * 而百炼非对称检索要求语料侧使用 {@code text_type=document}（官方文档：查询短语设置 query，
     * 感兴趣的文档设置 document），因此本适配器覆写为 DOCUMENT 批量编码。
     * <p>
     * 保留调用方的 {@link BatchingStrategy} 分批（如 TokenCountBatchingStrategy），
     * 更细粒度的 API 行数上限由底层客户端按候选 {@code params.batch-size} 再次切分。
     * {@link EmbeddingOptions} 被忽略——维度始终由候选配置 {@code dimension} 决定，
     * 保证与向量库 schema 一致。
     */
    @Override
    public @NonNull List<float@NonNull []> embed(@NonNull List<Document> documents,
                                                  @NonNull EmbeddingOptions options,
                                                  @NonNull BatchingStrategy batchingStrategy) {
        List<float[]> embeddings = new ArrayList<>(documents.size());
        for (List<Document> batch : batchingStrategy.batch(documents)) {
            List<String> texts = batch.stream().map(this::getEmbeddingContent).toList();
            embeddings.addAll(delegate.embedBatch(texts, EmbeddingType.DOCUMENT));
        }
        if (embeddings.size() != documents.size()) {
            throw new IllegalStateException(
                "Embeddings must have the same number as that of the documents: "
                    + embeddings.size() + " != " + documents.size());
        }
        return embeddings;
    }

    /**
     * 批量嵌入调用入口（{@code EmbeddingRequest} 携带 instructions 列表）。
     * <p>
     * 走 QUERY 编码路径（用于 answer-relevance 等查询侧场景），
     * 每个子批次由底层客户端通过结构化并发并行执行。
     */
    @Override
    public @NonNull EmbeddingResponse call(@NonNull EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        List<float[]> vectors = delegate.embedBatch(texts, EmbeddingType.QUERY);
        List<Embedding> embeddings = new ArrayList<>(vectors.size());
        for (int i = 0; i < vectors.size(); i++) {
            embeddings.add(new Embedding(vectors.get(i), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    /**
     * 批量嵌入（按 QUERY 编码）— 供需要直接 List&lt;String&gt; API 的调用方使用。
     */
    @Override
    public @NonNull List<float[]> embed(@NonNull List<String> texts) {
        return delegate.embedBatch(texts, EmbeddingType.QUERY);
    }

    /**
     * 向量维度（用于 PgVectorStore schema 校验等）。
     * <p>
     * 委托给 SPI 的 {@link EmbeddingCapable#dimension()}。
     */
    @Override
    public int dimensions() {
        return delegate.dimension();
    }
}
