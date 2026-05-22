package com.smart.rag.rag.etl;

import com.smart.rag.rag.chunk.ChunkStrategy;
import com.smart.rag.rag.chunk.ChunkStrategyFactory;
import com.smart.rag.rag.config.DocumentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 策略化文档分块变换器
 * <p>
 * 由 {@link ChunkStrategyFactory} 按 YAML 配置路由到具体分块策略。
 * </p>
 */
@Component
public class StrategyTransformer implements Transformer {

    private static final Logger log = LoggerFactory.getLogger(StrategyTransformer.class);

    private final ChunkStrategyFactory strategyFactory;
    private final DocumentProperties properties;

    public StrategyTransformer(ChunkStrategyFactory strategyFactory,
                               DocumentProperties properties) {
        this.strategyFactory = strategyFactory;
        this.properties = properties;
    }

    @Override
    public List<Document> transform(List<Document> documents, String sourceFileName) {
        ChunkStrategy strategy = strategyFactory.getStrategy(properties.getChunkStrategy());
        List<Document> chunks = strategy.chunk(documents, sourceFileName);
        log.info("Transformed {} docs → {} chunks (strategy={})",
                documents.size(), chunks.size(), strategy.strategyName());
        return chunks;
    }
}
