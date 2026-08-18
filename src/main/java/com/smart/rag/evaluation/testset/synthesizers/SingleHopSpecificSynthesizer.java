package com.smart.rag.evaluation.testset.synthesizers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.evaluation.testset.graph.KnowledgeGraph;
import com.smart.rag.evaluation.testset.graph.Node;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单跳合成器（翻译 ragas SingleHopSpecificQuerySynthesizer）。
 * <p>
 * 候选：有实体的 CHUNK 节点；每节点 ceil(n/节点数) 个场景，
 * 主题词取节点实体，persona 经匹配筛选，(节点,主题) 去重优先采样。
 * </p>
 */
public final class SingleHopSpecificSynthesizer extends QuerySynthesizer {

    public SingleHopSpecificSynthesizer(ChatClient cheapClient, ChatClient mainClient,
                                        ObjectMapper objectMapper, long seed) {
        super(cheapClient, mainClient, objectMapper, seed);
    }

    @Override
    public String name() {
        return "single_hop_specific_query_synthesizer";
    }

    @Override
    public boolean isAvailable(KnowledgeGraph kg) {
        return !kg.nodesWithEntities().isEmpty();
    }

    @Override
    public List<Scenario> generateScenarios(int n, KnowledgeGraph kg, List<Persona> personas) {
        var nodes = kg.nodesWithEntities();
        if (nodes.isEmpty()) {
            throw new IllegalStateException("No nodes found with the `entities` property.");
        }
        int samplesPerNode = (int) Math.ceil((double) n / nodes.size());
        var scenarios = new ArrayList<Scenario>();
        for (var node : nodes) {
            if (scenarios.size() >= n) {
                break;
            }
            var themes = List.copyOf(node.entities());
            if (themes.isEmpty()) {
                continue;
            }
            var personaConcepts = matchPersonas(themes, personas);
            var valid = validPersonas(personaConcepts, themes, personas);
            if (valid.isEmpty()) {
                continue;
            }
            // 上一节点的批次可能已逼近 n，按剩余预算封顶避免超出请求的规模（多出的场景就是多出的 LLM 调用）
            int remaining = n - scenarios.size();
            scenarios.addAll(sampleCombinations(node, themes, valid,
                    Math.min(samplesPerNode, remaining)));
        }
        return scenarios;
    }

    /** 翻译 sample_combinations：全部 (term×persona×style×length) 洗牌后，新鲜 term 优先、重复 term 补位。 */
    private List<Scenario> sampleCombinations(Node node, List<String> themes,
                                              List<Persona> personas, int numSamples) {
        var all = new ArrayList<Map<String, Object>>();
        for (var theme : themes) {
            for (var persona : personas) {
                for (var style : QueryStyle.values()) {
                    for (var length : QueryLength.values()) {
                        all.add(Map.of("term", theme, "persona", persona,
                                "style", style, "length", length));
                    }
                }
            }
        }
        Collections.shuffle(all, random);

        // 分区而非边选边记：新鲜 term 全部排前、重复 term 依洗牌序补位，凑满 numSamples
        var fresh = new ArrayList<Map<String, Object>>();
        var duplicates = new ArrayList<Map<String, Object>>();
        var seenTerms = new LinkedHashSet<String>();
        for (var sample : all) {
            if (seenTerms.add((String) sample.get("term"))) {
                fresh.add(sample);
            } else {
                duplicates.add(sample);
            }
        }
        fresh.addAll(duplicates);

        var selected = new ArrayList<Scenario>();
        for (var sample : fresh) {
            if (selected.size() >= numSamples) {
                break;
            }
            selected.add(new SingleHopScenario((String) sample.get("term"), node,
                    (Persona) sample.get("persona"), (QueryStyle) sample.get("style"),
                    (QueryLength) sample.get("length")));
        }
        return selected;
    }

    @Override
    public GeneratedSample generateSample(Scenario scenario) {
        if (!(scenario instanceof SingleHopScenario single)) {
            throw new IllegalArgumentException("scenario 类型应为 SingleHopScenario");
        }
        var context = single.node().pageContent();
        var prompt = TestsetPrompts.SINGLE_HOP_QA.formatted(
                single.persona().name(), single.persona().roleDescription(),
                single.term(), single.style().value(), single.length().value(), context);
        var qa = generateQueryAnswer(prompt);
        return new GeneratedSample(
                qa.getOrDefault("query", ""),
                qa.getOrDefault("answer", ""),
                List.of(context),
                List.of(single.node().id()),
                single.persona().name(), single.style().value(), single.length().value(),
                name());
    }
}
