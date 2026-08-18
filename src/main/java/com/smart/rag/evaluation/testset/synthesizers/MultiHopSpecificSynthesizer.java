package com.smart.rag.evaluation.testset.synthesizers;

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

/**
 * 多跳具体合成器（翻译 ragas MultiHopSpecificQuerySynthesizer）。
 * <p>
 * 数据源：实体重叠三元组（findTwoNodesSingleRel）。主题组取重叠实体对，
 * persona 匹配 + 多样性采样，QA 生成要求跨 &lt;1-hop&gt;/&lt;2-hop&gt; 段落组合。
 * </p>
 */
public final class MultiHopSpecificSynthesizer extends QuerySynthesizer {

    public MultiHopSpecificSynthesizer(ChatClient cheapClient, ChatClient mainClient,
                                       ObjectMapper objectMapper, long seed) {
        super(cheapClient, mainClient, objectMapper, seed);
    }

    @Override
    public String name() {
        return "multi_hop_specific_query_synthesizer";
    }

    @Override
    public boolean isAvailable(KnowledgeGraph kg) {
        return !GraphAlgorithms.findTwoNodesSingleRel(
                kg, rel -> rel.type() == RelationshipType.ENTITY_OVERLAP).isEmpty();
    }

    @Override
    public List<Scenario> generateScenarios(int n, KnowledgeGraph kg, List<Persona> personas) {
        var triplets = GraphAlgorithms.findTwoNodesSingleRel(
                kg, rel -> rel.type() == RelationshipType.ENTITY_OVERLAP);
        if (triplets.isEmpty()) {
            throw new IllegalStateException(
                    "No clusters found in the knowledge graph (entity overlap).");
        }
        int samplesPerCluster = (int) Math.ceil((double) n / triplets.size());
        var scenarios = new ArrayList<Scenario>();
        for (var triplet : triplets) {
            if (scenarios.size() >= n) {
                break;
            }
            var overlapped = overlappedItems(triplet.relationship());
            if (overlapped.isEmpty()) {
                continue;
            }
            var themes = overlapped.stream()
                    .flatMap(pair -> pair.stream()).distinct().toList();
            // 匹配调用一次（ragas 同款），persona 过滤按组合进行（prepare_combinations 语义）
            var personaConcepts = matchPersonas(themes, personas);
            var nodes = List.of(triplet.a(), triplet.b());
            for (var group : overlapped) {
                if (scenarios.size() >= n) {
                    break;
                }
                var valid = validPersonas(personaConcepts, group, personas);
                if (valid.isEmpty()) {
                    continue;
                }
                scenarios.addAll(sampleDiverseCombinations(group, valid, nodes,
                        samplesPerCluster));
            }
        }
        return scenarios;
    }

    /** 重叠实体对：properties.overlappedItems 的 "x~y" 串还原为 [x, y]。 */
    private static List<List<String>> overlappedItems(
            com.smart.rag.evaluation.testset.graph.Relationship rel) {
        if (!(rel.properties().get("overlappedItems") instanceof List<?> items)) {
            return List.of();
        }
        return items.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(item -> {
                    int split = item.indexOf('~');
                    return split > 0
                            ? List.of(item.substring(0, split), item.substring(split + 1))
                            : List.of(item);
                })
                .toList();
    }

    @Override
    public GeneratedSample generateSample(Scenario scenario) {
        if (!(scenario instanceof MultiHopScenario multi)) {
            // sealed 体系外的类型不匹配是编程错误，用 IllegalStateException
            throw new IllegalStateException("scenario 类型应为 MultiHopScenario");
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
