package com.smart.rag.chat.service.impl;

import com.smart.rag.chat.dto.ModelVO;
import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import com.smart.rag.infrastructure.llm.registry.RegistrySnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * ModelServiceImpl.listModelDetails 单元测试。
 * <p>
 * 覆盖契约：能力映射、过滤、available 标记、排序稳定性。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("模型目录详情（listModelDetails）")
class ModelServiceImplDetailTest {

    @Mock
    private LlmClientRegistry llmRegistry;

    @InjectMocks
    private ModelServiceImpl modelService;

    private CapabilityClient client(String id, String provider, String model, LlmCapability cap) {
        CapabilityClient c = org.mockito.Mockito.mock(CapabilityClient.class);
        when(c.candidateId()).thenReturn(id);
        when(c.providerId()).thenReturn(provider);
        when(c.modelName()).thenReturn(model);
        when(c.capability()).thenReturn(cap);
        return c;
    }

    private void stubSnapshot(Map<String, CapabilityClient> clients, Set<String> disabled) {
        RegistrySnapshot snap = new RegistrySnapshot(
                clients, Map.of(), Map.of(), Map.of(), Map.of(), disabled);
        when(llmRegistry.snapshot()).thenReturn(snap);
    }

    @Test
    @DisplayName("返回全部能力模型，带 capability 标签")
    void returnsAllWithCapability() {
        Map<String, CapabilityClient> clients = new LinkedHashMap<>();
        clients.put("deepseek-chat", client("deepseek-chat", "deepseek", "deepseek-chat", LlmCapability.CHAT));
        clients.put("bailian-embed", client("bailian-embed", "bailian", "text-embedding-v3", LlmCapability.EMBEDDING));
        clients.put("bge-reranker", client("bge-reranker", "baidu", "bge-reranker-base", LlmCapability.RERANKING));
        stubSnapshot(clients, Set.of());

        List<ModelVO> result = modelService.listModelDetails(null);

        assertThat(result).hasSize(3);
        assertThat(result).extracting(ModelVO::capability)
                .containsExactlyInAnyOrder("CHAT", "EMBEDDING", "RERANKING");
        // 按 capability → provider → id 排序，CHAT 应在最前
        assertThat(result.get(0).capability()).isEqualTo("CHAT");
    }

    @Test
    @DisplayName("按 capability=CHAT 过滤，只返回 CHAT 模型")
    void filterByCapability() {
        Map<String, CapabilityClient> clients = new LinkedHashMap<>();
        clients.put("deepseek-chat", client("deepseek-chat", "deepseek", "deepseek-chat", LlmCapability.CHAT));
        clients.put("glm-4-air", client("glm-4-air", "zhipu", "glm-4-air", LlmCapability.CHAT));
        clients.put("bailian-embed", client("bailian-embed", "bailian", "text-embedding-v3", LlmCapability.EMBEDDING));
        stubSnapshot(clients, Set.of());

        List<ModelVO> result = modelService.listModelDetails("CHAT");

        assertThat(result).hasSize(2);
        assertThat(result).allSatisfy(vo -> assertThat(vo.capability()).isEqualTo("CHAT"));
        // 同 capability 内按 provider 排序：deepseek < zhipu
        assertThat(result.get(0).provider()).isEqualTo("deepseek");
        assertThat(result.get(1).provider()).isEqualTo("zhipu");
    }

    @Test
    @DisplayName("运行时禁用的模型 available=false")
    void disabledMarkedUnavailable() {
        Map<String, CapabilityClient> clients = new LinkedHashMap<>();
        clients.put("deepseek-chat", client("deepseek-chat", "deepseek", "deepseek-chat", LlmCapability.CHAT));
        clients.put("glm-4-air", client("glm-4-air", "zhipu", "glm-4-air", LlmCapability.CHAT));
        stubSnapshot(clients, Set.of("glm-4-air"));

        List<ModelVO> result = modelService.listModelDetails(null);

        Map<String, ModelVO> byId = result.stream()
                .collect(java.util.stream.Collectors.toMap(ModelVO::id, v -> v));
        assertThat(byId.get("deepseek-chat").available()).isTrue();
        assertThat(byId.get("glm-4-air").available()).isFalse();
    }

    @Test
    @DisplayName("空注册表返回空列表")
    void emptyRegistry() {
        stubSnapshot(new LinkedHashMap<>(), Set.of());

        List<ModelVO> result = modelService.listModelDetails(null);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("capability 大小写不敏感过滤")
    void caseInsensitiveCapability() {
        Map<String, CapabilityClient> clients = new LinkedHashMap<>();
        clients.put("deepseek-chat", client("deepseek-chat", "deepseek", "deepseek-chat", LlmCapability.CHAT));
        stubSnapshot(clients, Set.of());

        List<ModelVO> result = modelService.listModelDetails("chat");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).capability()).isEqualTo("CHAT");
    }

    @Test
    @DisplayName("listChatModelIds 仅返回 CHAT 模型 id，过滤 embedding/reranking")
    void listChatModelIdsFiltersNonChat() {
        Map<String, CapabilityClient> clients = new LinkedHashMap<>();
        clients.put("deepseek-chat", client("deepseek-chat", "deepseek", "deepseek-chat", LlmCapability.CHAT));
        clients.put("glm-4-air", client("glm-4-air", "zhipu", "glm-4-air", LlmCapability.CHAT));
        clients.put("bailian-embed", client("bailian-embed", "bailian", "text-embedding-v3", LlmCapability.EMBEDDING));
        clients.put("bge-reranker", client("bge-reranker", "baidu", "bge-reranker-base", LlmCapability.RERANKING));
        stubSnapshot(clients, Set.of());

        List<String> result = modelService.listChatModelIds();

        assertThat(result).containsExactlyInAnyOrder("deepseek-chat", "glm-4-air");
        assertThat(result).doesNotContain("bailian-embed", "bge-reranker");
    }

    @Test
    @DisplayName("listChatModelIds 空注册表返回空列表")
    void listChatModelIdsEmpty() {
        stubSnapshot(new LinkedHashMap<>(), Set.of());

        List<String> result = modelService.listChatModelIds();

        assertThat(result).isEmpty();
    }
}
