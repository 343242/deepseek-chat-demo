package com.demo.chat.rag.chunk;

import com.demo.chat.rag.config.DocumentProperties;
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
 *   <li>子切分存入向量库（用于精准检索）</li>
 *   <li>每个子切分的 metadata 携带 parentId + parentContent</li>
 *   <li>检索命中子切分后，由 {@link com.demo.chat.rag.chunk.ParentDocumentPostProcessor}
 *       将子切分替换为父文档内容，提供完整上下文</li>
 * </ul>
 *
 * <p>优势：小切分保证检索精度，大上下文保证 LLM 理解完整性。</p>
 */
@Component
public class ParentChildChunkStrategy implements ChunkStrategy {

    private static final Logger log = LoggerFactory.getLogger(ParentChildChunkStrategy.class);

    /** metadata key: 父文档 ID */
    public static final String META_PARENT_ID = "parentId";
    /** metadata key: 父文档完整内容 */
    public static final String META_PARENT_CONTENT = "parentContent";
    /** metadata key: 是否为父文档 */
    public static final String META_IS_PARENT = "isParent";
    /** metadata key: 在父文档内的子切分序号 */
    public static final String META_CHILD_INDEX_IN_PARENT = "childIndexInParent";
    /** metadata key: 该父文档的子切分总数 */
    public static final String META_CHILD_COUNT = "childCount";

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
        int parentSize = properties.getParentChunkSize();
        int childSize = properties.getChildChunkSize();

        TokenTextSplitter parentSplitter = TokenTextSplitter.builder()
                .withChunkSize(parentSize)
                .build();

        TokenTextSplitter childSplitter = TokenTextSplitter.builder()
                .withChunkSize(childSize)
                .build();

        List<Document> childChunks = new ArrayList<>();
        int globalChildIndex = 0;
        int parentCount = 0;

        for (Document doc : documents) {
            // === 第一层：切分为父文档 ===
            List<Document> parents = parentSplitter.apply(List.of(doc));

            for (Document parent : parents) {
                String parentId = UUID.randomUUID().toString();
                String parentContent = parent.getText();
                parentCount++;

                // === 第二层：将父文档切分为子切分 ===
                List<Document> children = childSplitter.apply(List.of(parent));

                for (int i = 0; i < children.size(); i++) {
                    Document child = children.get(i);

                    // 附加子切分元数据
                    child.getMetadata().put(META_PARENT_ID, parentId);
                    child.getMetadata().put(META_PARENT_CONTENT, parentContent);
                    child.getMetadata().put(META_IS_PARENT, false);
                    child.getMetadata().put(META_CHILD_INDEX_IN_PARENT, i);
                    child.getMetadata().put(META_CHILD_COUNT, children.size());
                    child.getMetadata().put("source", sourceFileName);
                    child.getMetadata().put("chunkIndex", globalChildIndex);
                    child.getMetadata().put("chunkType", "child");

                    childChunks.add(child);
                    globalChildIndex++;
                }
            }
        }

        // 回写 totalChunks
        for (Document child : childChunks) {
            child.getMetadata().put("totalChunks", childChunks.size());
        }

        log.info("[ParentChildChunk] {} raw docs → {} parents → {} children " +
                        "(parentSize={}, childSize={}, source={})",
                documents.size(), parentCount, childChunks.size(),
                parentSize, childSize, sourceFileName);

        return childChunks;
    }
}
