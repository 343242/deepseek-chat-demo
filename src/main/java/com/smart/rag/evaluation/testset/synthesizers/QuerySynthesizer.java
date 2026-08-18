package com.smart.rag.evaluation.testset.synthesizers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.evaluation.util.JsonExtractorUtil;
import com.smart.rag.evaluation.testset.graph.KnowledgeGraph;
import com.smart.rag.evaluation.testset.graph.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

/**
 * 合成器基类（对应 ragas BaseSynthesizer / MultiHopQuerySynthesizer 公共部分）。
 * <p>
 * 双客户端分工：cheapClient（抽取候选）做 persona 匹配与概念组合，
 * mainClient（出题主模型）做问答生成——与 Python 参照实现一致。
 * 场景/样本阶段的失败处理在编排器的 ScopedTasks fork 层（单条失败不中断批次），
 * 本类抛出的异常由上层兜住。
 * </p>
 */
public abstract class QuerySynthesizer {

    protected final org.springframework.ai.chat.client.ChatClient cheapClient;
    protected final org.springframework.ai.chat.client.ChatClient mainClient;
    protected final ObjectMapper objectMapper;
    protected final Random random;

    protected QuerySynthesizer(org.springframework.ai.chat.client.ChatClient cheapClient,
                               org.springframework.ai.chat.client.ChatClient mainClient,
                               ObjectMapper objectMapper,
                               long seed) {
        this.cheapClient = cheapClient;
        this.mainClient = mainClient;
        this.objectMapper = objectMapper;
        this.random = new Random(seed);
    }

    /** 合成器名（入库 tag 用，与 ragas 命名一致）。 */
    public abstract String name();

    /** 该合成器在当前图谱上是否有可用的数据源（无则由编排器跳过，对应 ragas 的降级）。 */
    public abstract boolean isAvailable(KnowledgeGraph kg);

    /** 生成 n 个场景（翻译各合成器 _generate_scenarios）。 */
    public abstract List<Scenario> generateScenarios(int n, KnowledgeGraph kg, List<Persona> personas);

    /** 由场景生成样本（翻译 _generate_sample）。 */
    public abstract GeneratedSample generateSample(Scenario scenario);

    /** persona × 主题匹配（cheap 模型）。失败抛异常，由编排层兜住。JsonNode 导航只取 mapping 字段，容忍 LLM 杂键。 */
    protected Map<String, List<String>> matchPersonas(List<String> themes, List<Persona> personas) {
        var themesText = String.join(", ", themes);
        var personasText = personas.stream()
                .map(p -> p.name() + "（" + p.roleDescription() + "）")
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
        var prompt = TestsetPrompts.PERSONA_MATCHING.formatted(themesText, personasText);
        var response = cheapClient.prompt().user(prompt).call().content();
        try {
            var root = objectMapper.readTree(JsonExtractorUtil.extractJson(response));
            var result = new HashMap<String, List<String>>();
            var mapping = root.path("mapping");
            if (mapping.isObject()) {
                mapping.fields().forEachRemaining(entry -> {
                    var concepts = new ArrayList<String>();
                    entry.getValue().forEach(node -> {
                        if (node.isTextual()) {
                            concepts.add(node.asText());
                        }
                    });
                    result.put(entry.getKey(), List.copyOf(concepts));
                });
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("persona 匹配解析失败: " + e.getMessage(), e);
        }
    }

    /** 有效 persona 过滤（翻译 prepare_combinations 的 valid_personas 语义）：任一 item 被匹配概念包含。 */
    protected static List<Persona> validPersonas(Map<String, List<String>> personaConcepts,
                                                 List<String> items,
                                                 List<Persona> personas) {
        var result = new ArrayList<Persona>();
        for (var persona : personas) {
            var concepts = personaConcepts.getOrDefault(persona.name(), List.of()).stream()
                    .map(c -> c.toLowerCase()).toList();
            boolean matches = items.stream().anyMatch(item ->
                    concepts.contains(item.toLowerCase()));
            if (matches) {
                result.add(persona);
            }
        }
        return result;
    }

    /** 问答生成（main 模型）共享调用。JsonNode 导航取 query/answer 字段，容忍杂键。 */
    protected Map<String, String> generateQueryAnswer(String prompt) {
        var response = mainClient.prompt().user(prompt).call().content();
        try {
            var root = objectMapper.readTree(JsonExtractorUtil.extractJson(response));
            return Map.of(
                    "query", root.path("query").asText(""),
                    "answer", root.path("answer").asText(""));
        } catch (Exception e) {
            throw new IllegalStateException("问答生成解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 多样性采样（翻译 sample_diverse_combinations）：洗牌后按
     * "组合×persona 未见过 → 取；否则 style/length 轮转补位" 的原逻辑。
     */
    protected List<MultiHopScenario> sampleDiverseCombinations(
            List<String> combination, List<Persona> personas, List<Node> nodes,
            int numSamples) {
        var all = new ArrayList<Map<String, Object>>();
        for (var persona : personas) {
            for (var style : QueryStyle.values()) {
                for (var length : QueryLength.values()) {
                    all.add(Map.of("combination", combination, "persona", persona,
                            "nodes", nodes, "style", style, "length", length));
                }
            }
        }
        Collections.shuffle(all, random);

        var combinationPersonaCount = new HashMap<String, Set<String>>();
        var styleCount = new HashMap<QueryStyle, Integer>();
        var lengthCount = new HashMap<QueryLength, Integer>();
        var selected = new ArrayList<MultiHopScenario>();
        for (var sample : all) {
            if (selected.size() >= numSamples) {
                break;
            }
            var persona = (Persona) sample.get("persona");
            var style = (QueryStyle) sample.get("style");
            var length = (QueryLength) sample.get("length");
            var comboKey = String.join("|", combination);

            if (!combinationPersonaCount.computeIfAbsent(comboKey, k -> new LinkedHashSet<>())
                    .contains(persona.name())) {
                combinationPersonaCount.get(comboKey).add(persona.name());
                selected.add(new MultiHopScenario(combination, nodes, persona, style, length));
            } else if (styleCount.getOrDefault(style, 0) < maxCount(styleCount) + 1) {
                styleCount.merge(style, 1, Integer::sum);
                selected.add(new MultiHopScenario(combination, nodes, persona, style, length));
            } else if (lengthCount.getOrDefault(length, 0) < maxCount(lengthCount) + 1) {
                lengthCount.merge(length, 1, Integer::sum);
                selected.add(new MultiHopScenario(combination, nodes, persona, style, length));
            }
        }
        return selected;
    }

    private static <T> int maxCount(Map<T, Integer> counts) {
        return counts.values().stream().max(Integer::compare).orElse(0);
    }

    /** 多跳 context 拼接（翻译 make_contexts）：每段带 <i>-hop> 标签。 */
    protected static List<String> makeContexts(List<Node> nodes,
                                               Function<Node, String> content) {
        var contexts = new ArrayList<String>();
        for (int i = 0; i < nodes.size(); i++) {
            contexts.add("<" + (i + 1) + "-hop>\n\n" + content.apply(nodes.get(i)));
        }
        return contexts;
    }
}
