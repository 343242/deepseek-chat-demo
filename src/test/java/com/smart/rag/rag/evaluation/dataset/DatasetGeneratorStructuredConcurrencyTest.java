package com.smart.rag.rag.evaluation.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.common.concurrent.DefaultScopedTasks;
import com.smart.rag.common.concurrent.ScopeOptions;
import com.smart.rag.common.concurrent.ScopePolicy;
import com.smart.rag.common.concurrent.ScopedTasks;
import com.smart.rag.common.concurrent.TaskScope;
import com.smart.rag.rag.evaluation.config.EvaluationProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("DatasetGenerator structured concurrency")
class DatasetGeneratorStructuredConcurrencyTest {

    @Test
    @DisplayName("generate opens scoped tasks for chunk question generation")
    void generate_opensScopedTasksForChunkQuestionGeneration() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class);
        DatasetRepository datasetRepo = mock(DatasetRepository.class);
        RecordingScopedTasks scopedTasks = new RecordingScopedTasks();
        EvaluationProperties properties = new EvaluationProperties();
        properties.getDataset().setSampleSize(1);
        properties.getDataset().setQuestionsPerChunk(1);
        properties.getRunner().setConcurrency(2);
        EvaluationDataset inserted = new EvaluationDataset(
                100L, "dataset", "desc", 1, "llm_generated", "judge", 0, null, null, null);

        when(datasetRepo.insertDataset(org.mockito.ArgumentMatchers.any(EvaluationDataset.class))).thenReturn(inserted);
        when(jdbc.queryForList(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(Map.of("id", 7L, "content", "chunk text")));

        DatasetGenerator generator = new DatasetGenerator(
                jdbc,
                chatClientBuilder,
                properties,
                datasetRepo,
                new ObjectMapper(),
                scopedTasks
        );

        EvaluationDataset result = generator.generate("dataset", 1L);

        assertThat(result.id()).isEqualTo(100L);
        assertThat(scopedTasks.scopeNames()).containsExactly("dataset-generate");
        assertThat(scopedTasks.lastOptions().policy()).isEqualTo(ScopePolicy.COLLECT_ALL);
        assertThat(scopedTasks.lastOptions().maxConcurrency()).isEqualTo(2);
        org.mockito.Mockito.verify(datasetRepo).insertDataset(org.mockito.ArgumentMatchers.any(EvaluationDataset.class));
        org.mockito.Mockito.verify(jdbc).queryForList(anyString(), eq("{\"userId\":\"1\"}"), eq(1));
    }

    private static final class RecordingScopedTasks implements ScopedTasks {

        private final ScopedTasks delegate = new DefaultScopedTasks();
        private final List<String> scopeNames = new ArrayList<>();
        private ScopeOptions lastOptions;

        @Override
        public TaskScope open(String name) {
            scopeNames.add(name);
            return delegate.open(name);
        }

        @Override
        public TaskScope open(String name, ScopePolicy policy) {
            scopeNames.add(name);
            return delegate.open(name, policy);
        }

        @Override
        public TaskScope open(String name, ScopeOptions options) {
            scopeNames.add(name);
            lastOptions = options;
            return delegate.open(name, options);
        }

        @Override
        public TaskScope open(String name, ScopeOptions options, ExecutorService executor) {
            scopeNames.add(name);
            lastOptions = options;
            return delegate.open(name, options, executor);
        }

        private List<String> scopeNames() {
            return List.copyOf(scopeNames);
        }

        private ScopeOptions lastOptions() {
            return lastOptions;
        }
    }
}
