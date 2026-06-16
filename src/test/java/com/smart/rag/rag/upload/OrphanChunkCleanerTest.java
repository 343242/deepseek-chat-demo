package com.smart.rag.rag.upload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W4 R1-M4: {@link OrphanChunkCleaner#extractUploadId(String)} 正则提取回归测试。
 * <p>
 * 旧实现用 {@code split("/")[2]} 依赖固定路径层数，路径结构变化时 mis-parse 会误删存活分片；
 * 新实现用正则，不匹配则跳过（绝不删除）。
 */
@DisplayName("W4 R1-M4: OrphanChunkCleaner uploadId 提取")
class OrphanChunkCleanerTest {

    @Test
    @DisplayName("标准分片路径 → 提取 uploadId")
    void extractsUploadIdFromWellFormedPath() {
        String objectName = "chunks/user123/123e4567-e89b-12d3-a456-426614174000/part-0";
        assertThat(OrphanChunkCleaner.extractUploadId(objectName))
                .contains("123e4567-e89b-12d3-a456-426614174000");
    }

    @Test
    @DisplayName("不同 part 索引也能匹配")
    void matchesVariousPartIndex() {
        String objectName = "chunks/u9/abcdef01-2345-6789-abcd-ef0123456789/part-99";
        assertThat(OrphanChunkCleaner.extractUploadId(objectName))
                .contains("abcdef01-2345-6789-abcd-ef0123456789");
    }

    @Test
    @DisplayName("非预期路径结构 → empty（绝不因解析失败而误删）")
    void returnsEmptyForUnmatchedPath() {
        assertThat(OrphanChunkCleaner.extractUploadId("weird/path")).isEmpty();
        assertThat(OrphanChunkCleaner.extractUploadId("chunks/only-two-levels")).isEmpty();
        assertThat(OrphanChunkCleaner.extractUploadId("documents/1/x.pdf")).isEmpty();
        // 路径层数超出预期（trailing segment）也不匹配
        assertThat(OrphanChunkCleaner.extractUploadId(
                "chunks/u/123e4567-e89b-12d3-a456-426614174000/part-0/extra")).isEmpty();
    }

    @Test
    @DisplayName("uploadId 非标准 36 位 hex 格式 → empty（旧代码会把短 id 当 parts[2] 误删）")
    void returnsEmptyForMalformedUploadId() {
        assertThat(OrphanChunkCleaner.extractUploadId("chunks/u/not-a-uuid/part-0")).isEmpty();
        assertThat(OrphanChunkCleaner.extractUploadId("chunks/u/shortid/part-0")).isEmpty();
    }
}
