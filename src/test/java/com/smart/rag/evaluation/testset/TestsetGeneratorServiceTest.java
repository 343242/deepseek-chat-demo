package com.smart.rag.evaluation.testset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.evaluation.config.EvaluationProperties;
import com.smart.rag.evaluation.dataset.DatasetRepository;
import com.smart.rag.evaluation.dataset.EvaluationDataset;
import com.smart.rag.evaluation.testset.transforms.ChunkEntityLoader;
import com.smart.rag.infrastructure.concurrent.DefaultScopedTasks;
import com.smart.rag.infrastructure.concurrent.ScopeOptions;
import com.smart.rag.infrastructure.concurrent.ScopePolicy;
import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.infrastructure.concurrent.TaskScope;
import com.smart.rag.infrastructure.llm.adapter.RewriteClientResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TestsetGeneratorService} 编排测试：LLM 链按罐头 JSON 桩化
 * （单条 JSON 同时含 themes/mapping 键，兼容两阶段解析，避免并发下的桩顺序问题）。
 */
@DisplayName("KG 测试集生成编排器")
class TestsetGeneratorServiceTest {

    private JdbcTemplate jdbc;
    private ChunkEntityLoader entityLoader;
    private RewriteClientResolver resolver;
    private DatasetRepository datasetRepo;
    private ChatClient cheapClient;
    private ChatClient mainClient;
    private RecordingScopedTasks scopedTasks;
    private EvaluationProperties properties;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        entityLoader = mock(ChunkEntityLoader.class);
        resolver = mock(RewriteClientResolver.class);
        datasetRepo = mock(DatasetRepository.class);
        scopedTasks = new RecordingScopedTasks();

        properties = new EvaluationProperties();
        properties.getDataset().setSize(2);
        properties.getDataset().setMaxChunks(5);
        properties.getDataset().setSynthesisModel(null);
        properties.getRunner().setConcurrency(2);
        properties.getRunner().setItemTimeoutSeconds(60);

        // cheap（抽取候选）与 main（出题，synthesis-model=null → 默认候选）分别桩化
        cheapClient = mockChatClient("""
                {"themes": ["多屏协同"], "mapping": {"一线业务人员": ["多屏协同"]}}""");
        mainClient = mockChatClient(
                "{\"query\": \"怎么用多屏协同？\", \"answer\": \"下拉控制中心打开超级终端。\"}");
        lenient().when(resolver.resolve("deepseek-v4-flash")).thenReturn(cheapClient);
        lenient().when(resolver.resolve(null)).thenReturn(mainClient);
        lenient().when(resolver.resolveDefault()).thenReturn(mainClient);

        lenient().when(datasetRepo.insertDataset(ArgumentMatchers.any(EvaluationDataset.class)))
                .thenReturn(new EvaluationDataset(
                        100L, "dataset", "desc", 1, "ragas_kg", "judge", 0, null, null, null));
        lenient().when(datasetRepo.insertItems(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static ChatClient mockChatClient(String cannedJson) {
        var client = mock(ChatClient.class);
        var spec = mock(ChatClient.ChatClientRequestSpec.class);
        var response = mock(ChatClient.CallResponseSpec.class);
        lenient().when(client.prompt()).thenReturn(spec);
        lenient().when(spec.user(anyString())).thenReturn(spec);
        lenient().when(spec.call()).thenReturn(response);
        lenient().when(response.content()).thenReturn(cannedJson);
        return client;
    }

    private static Map<String, Object> chunkRow(String id, String content, String embedding) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("content", content);
        row.put("metadata", Map.of("userId", "1"));
        row.put("embedding", embedding);
        return row;
    }

    @Test
    @DisplayName("全链路：采样→实体→主题→边→场景→样本→去重落库（含 ScopedTasks 断言）")
    void fullFlowGeneratesDedupedItems() {
        var id1 = "11111111-1111-1111-1111-111111111111";
        var id2 = "22222222-2222-2222-2222-222222222222";
        when(jdbc.queryForList(anyString(), anyString(), anyInt())).thenReturn(List.of(
                chunkRow(id1, "多屏协同需要登录同一华为账号。", "[0.1,0.9]"),
                chunkRow(id2, "畅连通话依赖华为账号。", "[0.9,0.1]")));
        when(entityLoader.loadEntities(anyList(), anyLong())).thenReturn(Map.of(
                id1, Set.of("多屏协同", "华为账号"),
                id2, Set.of("畅连", "华为账号")));
        var progressCount = new AtomicInteger();
        var service = new TestsetGeneratorService(jdbc, entityLoader, resolver, properties,
                datasetRepo, new ObjectMapper(), scopedTasks);

        var result = service.generate("dataset", 1L,
                (phase, current, total, message) -> progressCount.incrementAndGet());

        assertThat(result.id()).isEqualTo(100L);
        // 主模型两条罐头答案相同 → 问题去重后仅 1 条
        assertThat(result.items()).hasSize(1);
        var item = result.items().getFirst();
        assertThat(item.question()).isEqualTo("怎么用多屏协同？");
        assertThat(item.groundTruthAnswer()).isEqualTo("下拉控制中心打开超级终端。");
        assertThat(item.relevantChunkIds()).isSubsetOf(id1, id2);
        assertThat(item.tags()).contains("single_hop_specific_query_synthesizer");
        assertThat(progressCount.get()).isGreaterThanOrEqualTo(3);

        // 三段 ScopedTasks：主题抽取 / 场景 / 样本合成，均 COLLECT_ALL 且并发=2
        assertThat(scopedTasks.scopeNames()).containsExactly(
                "testset-extract", "testset-scenarios", "testset-synthesize");
        assertThat(scopedTasks.lastOptions().policy()).isEqualTo(ScopePolicy.COLLECT_ALL);
        assertThat(scopedTasks.lastOptions().maxConcurrency()).isEqualTo(2);
        verify(datasetRepo).insertItems(anyList());
        verify(datasetRepo).updateDatasetItemCount(100L, 1);
    }

    @Test
    @DisplayName("空采样提前返回：不建 KG、不调 LLM、条数为 0")
    void emptySampleReturnsEarly() {
        when(jdbc.queryForList(anyString(), anyString(), anyInt())).thenReturn(List.of());
        var service = new TestsetGeneratorService(jdbc, entityLoader, resolver, properties,
                datasetRepo, new ObjectMapper(), scopedTasks);

        var result = service.generate("dataset", 1L, null);

        assertThat(result.id()).isEqualTo(100L);
        assertThat(result.items()).isEmpty();
        assertThat(scopedTasks.scopeNames()).isEmpty();
        verify(datasetRepo).updateDatasetItemCount(100L, 0);
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
