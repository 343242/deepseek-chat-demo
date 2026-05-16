package com.demo.chat.rag.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * VectorStoreMapper 单元测试。
 * <p>
 * 通过 Mock JdbcTemplate 验证 SQL 参数绑定和结果解析逻辑。
 */
@ExtendWith(MockitoExtension.class)
class VectorStoreMapperTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private VectorStoreMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new VectorStoreMapper(jdbcTemplate, objectMapper);
    }

    @Nested
    @DisplayName("bm25Search")
    class Bm25Search {

        @Test
        @DisplayName("SQL 参数按正确顺序绑定")
        void bm25Search_parameterBinding() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyVararg()))
                    .thenReturn(Collections.emptyList());

            mapper.bm25Search("jiebacfg", "测试查询", "userId", "123", 10);

            ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate).query(anyString(), any(RowMapper.class), paramsCaptor.capture());

            Object[] params = paramsCaptor.getValue();
            // ftsConfig, sanitizedQuery, isolationField, isolationValue, ftsConfig, sanitizedQuery, topK
            assertThat(params).containsExactly("jiebacfg", "测试查询", "userId", "123", "jiebacfg", "测试查询", 10);
        }

        @Test
        @DisplayName("返回空结果时得到空列表")
        void bm25Search_emptyResult() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyVararg()))
                    .thenReturn(Collections.emptyList());

            List<Document> result = mapper.bm25Search("jiebacfg", "query", "userId", "1", 5);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("topK=1 只返回 1 条")
        void bm25Search_topK1() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyVararg()))
                    .thenReturn(Collections.emptyList());

            mapper.bm25Search("jiebacfg", "query", "teamId", "team1", 1);

            ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate).query(anyString(), any(RowMapper.class), paramsCaptor.capture());
            assertThat(paramsCaptor.getValue()[6]).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("batchFetchParents")
    class BatchFetchParents {

        @Test
        @DisplayName("空 parentIds 直接返回空 Map")
        void batchFetchParents_emptyInput() {
            Map<String, Document> result = mapper.batchFetchParents(Collections.emptySet());
            assertThat(result).isEmpty();
            verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), anyVararg());
        }

        @Test
        @DisplayName("单个 parentId 查询")
        void batchFetchParents_singleId() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyVararg()))
                    .thenReturn(Collections.emptyList());

            mapper.batchFetchParents(Set.of("parent-1"));

            verify(jdbcTemplate).query(contains("IN (?)"), any(RowMapper.class), any(Object[].class));
        }

        @Test
        @DisplayName("多个 parentId 生成正确数量的占位符")
        void batchFetchParents_multipleIds() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyVararg()))
                    .thenReturn(Collections.emptyList());

            mapper.batchFetchParents(Set.of("p1", "p2", "p3"));

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), any(Object[].class));
            assertThat(sqlCaptor.getValue()).contains("IN (?,?,?)");
        }
    }

    @Nested
    @DisplayName("insertFastTrackRow")
    class InsertFastTrackRow {

        @Test
        @DisplayName("写入时 metadata 包含 fastTrack=true")
        void insertFastTrackRow_containsMetadata() {
            when(jdbcTemplate.update(anyString(), any(), anyString())).thenReturn(1);

            mapper.insertFastTrackRow(100L, "测试内容", 1L, null);

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).update(contains("INSERT INTO vector_store"), eq("测试内容"), jsonCaptor.capture());

            String metadataJson = jsonCaptor.getValue();
            assertThat(metadataJson).contains("\"fastTrack\":true");
            assertThat(metadataJson).contains("\"documentId\":\"100\"");
            assertThat(metadataJson).contains("\"userId\":\"1\"");
            assertThat(metadataJson).doesNotContain("teamId");
        }

        @Test
        @DisplayName("带 teamId 时 metadata 包含 teamId")
        void insertFastTrackRow_withTeamId() {
            when(jdbcTemplate.update(anyString(), any(), anyString())).thenReturn(1);

            mapper.insertFastTrackRow(200L, "内容", 1L, 99L);

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).update(anyString(), eq("内容"), jsonCaptor.capture());

            String metadataJson = jsonCaptor.getValue();
            assertThat(metadataJson).contains("\"teamId\":\"99\"");
        }
    }

    @Nested
    @DisplayName("deleteFastTrackRows")
    class DeleteFastTrackRows {

        @Test
        @DisplayName("按 documentId 删除 fastTrack 行")
        void deleteFastTrackRows() {
            when(jdbcTemplate.update(anyString(), anyString())).thenReturn(1);

            mapper.deleteFastTrackRows(100L);

            verify(jdbcTemplate).update(contains("DELETE FROM vector_store"), eq("100"));
        }
    }

    @Nested
    @DisplayName("pairwiseCosineDistance")
    class PairwiseCosineDistance {

        @Test
        @DisplayName("少于 2 个 ID 时返回空 Map")
        void pairwise_lessThanTwo() {
            assertThat(mapper.pairwiseCosineDistance(null)).isEmpty();
            assertThat(mapper.pairwiseCosineDistance(Collections.emptyList())).isEmpty();
            assertThat(mapper.pairwiseCosineDistance(List.of("id1"))).isEmpty();
        }

        @Test
        @DisplayName("2 个 ID 时 SQL 包含正确的自连接条件")
        void pairwise_twoIds() {
            when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowCallbackHandler.class), any(Object[].class)))
                    .thenAnswer(invocation -> null);

            mapper.pairwiseCosineDistance(List.of("id1", "id2"));

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).query(sqlCaptor.capture(), any(org.springframework.jdbc.core.RowCallbackHandler.class), any(Object[].class));
            assertThat(sqlCaptor.getValue()).contains("a.id < b.id");
        }
    }
}
