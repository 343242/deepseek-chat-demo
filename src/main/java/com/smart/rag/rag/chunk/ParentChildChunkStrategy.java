package com.smart.rag.rag.chunk;

import com.smart.rag.rag.config.DocumentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 父文档-子切分策略（Parent-Child Chunking）
 * <p>
 * 两层切分：
 * <ol>
 *   <li>将原始文档切分为较大的「父文档」（如 2000 tokens）</li>
 *   <li>将每个父文档再切分为较小的「子切分」（如 500 tokens）</li>
 * </ol>
 *
 * <p>存储模型：</p>
 * <ul>
 *   <li>父文档存入向量库（标记 isParent=true），用于检索后回查</li>
 *   <li>子切分存入向量库（用于精准检索），metadata 只存 parentId（不含父文内容）</li>
 *   <li>检索命中子切分后，由 {@link ParentDocumentPostProcessor}
 *       通过 parentId 从向量库回查父文档，替换子切分为父文档内容</li>
 * </ul>
 *
 * <p>优势：小切分保证检索精度，大上下文保证 LLM 理解完整性。
 * 只存 parentId 避免向量库膨胀和脱敏/删除/迁移困难。</p>
 */
@Component
public class ParentChildChunkStrategy implements ChunkStrategy {

    private static final Logger log = LoggerFactory.getLogger(ParentChildChunkStrategy.class);

    /** metadata key: 父文档 ID */
    public static final String META_PARENT_ID = "parentId";
    /** metadata key: 是否为父文档 */
    public static final String META_IS_PARENT = "isParent";
    /** metadata key: 在父文档内的子切分序号 */
    public static final String META_CHILD_INDEX_IN_PARENT = "childIndexInParent";
    /** metadata key: 该父文档的子切分总数 */
    public static final String META_CHILD_COUNT = "childCount";
    /** metadata key: 父文档的向量 ID（用于回查） */
    public static final String META_PARENT_VECTOR_ID = "parentVectorId";

    private final DocumentProperties properties;

    public ParentChildChunkStrategy(DocumentProperties properties) {
        this.properties = properties;
    }

    @Override
    public String strategyName() {
        return "parent-child";
    }

    @Override
    public List<Document> chunk(List<Document> documents, String sourceFileName) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        int parentSize = properties.getParentChunkSize();
        int childSize = properties.getChildChunkSize();

        TokenTextSplitter parentSplitter = TokenTextSplitter.builder()
                .withChunkSize(parentSize)
                .build();

        TokenTextSplitter childSplitter = TokenTextSplitter.builder()
                .withChunkSize(childSize)
                .build();

        List<Document> allChunks = new ArrayList<>();
        int globalChildIndex = 0;
        int parentCount = 0;

        for (Document doc : documents) {
            // === 第一层：切分为父文档 ===
            List<Document> parents = parentSplitter.apply(List.of(doc));

            for (Document parent : parents) {
                String parentId = UUID.randomUUID().toString();
                parentCount++;

                // 父文档也写入输出列表（标记为 parent），后续一起存入向量库
                parent.getMetadata().put(META_IS_PARENT, true);
                parent.getMetadata().put("parentId", parentId);
                parent.getMetadata().put("source", sourceFileName);
                parent.getMetadata().put("chunkType", "parent");
                allChunks.add(parent);

                // === 第二层：将父文档切分为子切分 ===
                List<Document> children = childSplitter.apply(List.of(parent));

                for (int i = 0; i < children.size(); i++) {
                    Document child = children.get(i);

                    // 子切分只存 parentId，不存父文内容
                    child.getMetadata().put(META_PARENT_ID, parentId);
                    child.getMetadata().put(META_IS_PARENT, false);
                    child.getMetadata().put(META_CHILD_INDEX_IN_PARENT, i);
                    child.getMetadata().put(META_CHILD_COUNT, children.size());
                    child.getMetadata().put("source", sourceFileName);
                    child.getMetadata().put("chunkIndex", globalChildIndex);
                    child.getMetadata().put("chunkType", "child");

                    allChunks.add(child);
                    globalChildIndex++;
                }
            }
        }

        // 回写 totalChunks
        for (Document chunk : allChunks) {
            chunk.getMetadata().put("totalChunks", allChunks.size());
        }

        log.info("[ParentChildChunk] {} raw docs → {} parents + children = {} total " +
                        "(parentSize={}, childSize={}, source={})",
                documents.size(), parentCount, allChunks.size(),
                parentSize, childSize, sourceFileName);

        return allChunks;
    }
}
