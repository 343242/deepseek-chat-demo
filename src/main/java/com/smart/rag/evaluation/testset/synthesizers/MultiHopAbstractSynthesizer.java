package com.smart.rag.evaluation.testset.synthesizers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.evaluation.testset.graph.GraphAlgorithms;
import com.smart.rag.evaluation.testset.graph.KnowledgeGraph;
import com.smart.rag.evaluation.testset.graph.Node;
import com.smart.rag.evaluation.testset.graph.RelationshipType;
import com.smart.rag.evaluation.util.JsonExtractorUtil;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 多跳抽象合成器（翻译 ragas MultiHopAbstractQuerySynthesizer）。
 * <p>
 * 数据源：向量相似边的间接簇（findNIndirectClusters，纯 DFS 版——ragas 0.4.3
 * 抽象合成器的真实调用路径）。概念组合经 LLM 跨节点配对，主题为节点 themes。
 * </p>
 */
public final class MultiHopAbstractSynthesizer extends QuerySynthesizer {

    private static final int DEPTH_LIMIT = 3;

    public MultiHopAbstractSynthesizer(ChatClient cheapClient, ChatClient mainClient,
                                       ObjectMapper objectMapper, long seed) {
        super(cheapClient, mainClient, objectMapper, seed);
    }

    @Override
    public String name() {
        return "multi_hop_abstract_query_synthesizer";
    }

    @Override
    public boolean isAvailable(KnowledgeGraph kg) {
        try {
            GraphAlgorithms.findNIndirectClusters(kg,
                    rel -> rel.type() == RelationshipType.SIMILARITY, 1, DEPTH_LIMIT);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public List<Scenario> generateScenarios(int n, KnowledgeGraph kg, List<Persona> personas) {
        var clusters = GraphAlgorithms.findNIndirectClusters(kg,
                rel -> rel.type() == RelationshipType.SIMILARITY, n, DEPTH_LIMIT);
        if (clusters.isEmpty()) {
            throw new IllegalStateException(
                    "No clusters found in the knowledge graph (similarity).");
        }
        int samplesPerCluster = (int) Math.ceil((double) n / clusters.size());
        var scenarios = new ArrayList<Scenario>();
        for (var cluster : clusters) {
            if (scenarios.size() >= n) {
                break;
            }
            var nodes = List.copyOf(cluster);
            var conceptsPerNode = nodes.stream().map(Node::themesOrEntities).toList();
            var combinations = combineConcepts(conceptsPerNode, samplesPerCluster);
            if (combinations.isEmpty()) {
                continue;
            }
            var flattened = combinations.stream()
                    .flatMap(List::stream).distinct().toList();
            var personaConcepts = matchPersonas(flattened, personas);
            var valid = validPersonas(personaConcepts, flattened, personas);
            if (valid.isEmpty()) {
                continue;
            }
            for (var combination : combinations) {
                if (scenarios.size() >= n) {
                    break;
                }
                // 翻译 prepare_combinations 的 valid_nodes：组合概念命中节点主题的节点参与
                var validNodes = nodes.stream()
                        .filter(node -> node.themesOrEntities().stream()
                                .anyMatch(theme -> combination.contains(theme)))
                        .toList();
                if (validNodes.size() < 2) {
                    validNodes = nodes;
                }
                scenarios.addAll(sampleDiverseCombinations(combination, valid,
                        validNodes, samplesPerCluster));
            }
        }
        return scenarios;
    }

    /** 概念组合（cheap 模型，翻译 ConceptCombinationPrompt 调用）。 */
    private List<List<String>> combineConcepts(List<List<String>> conceptsPerNode,
                                               int maxCombinations) {
        var listsText = conceptsPerNode.stream()
                .map(list -> "[" + String.join(", ", list) + "]")
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
        var prompt = TestsetPrompts.CONCEPT_COMBINATION.formatted(listsText, maxCombinations);
        var response = cheapClient.prompt().user(prompt).call().content();
        try {
            var root = objectMapper.readTree(JsonExtractorUtil.extractJson(response));
            var combinations = new ArrayList<List<String>>();
            root.path("combinations").forEach(combination -> {
                if (combination.isArray()) {
                    var concepts = new ArrayList<String>();
                    combination.forEach(node -> {
                        if (node.isTextual()) {
                            concepts.add(node.asText());
                        }
                    });
                    combinations.add(List.copyOf(concepts));
                }
            });
            return combinations;
        } catch (Exception e) {
            throw new IllegalStateException("概念组合解析失败: " + e.getMessage(), e);
        }
    }

    @Override
    public GeneratedSample generateSample(Scenario scenario) {
        if (!(scenario instanceof MultiHopScenario multi)) {
            throw new IllegalArgumentException("scenario 类型应为 MultiHopScenario");
        }
        var contexts = makeContexts(multi.nodes(), Node::pageContent);
        var prompt = TestsetPrompts.MULTI_HOP_QA.formatted(
                multi.persona().name(), multi.persona().roleDescription(),
                String.join("、", multi.combinations()),
                multi.style().value(), multi.length().value(),
                String.join("\n\n", contexts));
        var qa = generateQueryAnswer(prompt);
        return new GeneratedSample(
                qa.getOrDefault("query", ""),
                qa.getOrDefault("answer", ""),
                contexts,
                multi.nodes().stream().map(Node::id).toList(),
                multi.persona().name(), multi.style().value(), multi.length().value(),
                name());
    }
}
