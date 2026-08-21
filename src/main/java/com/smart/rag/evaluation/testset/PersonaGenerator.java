package com.smart.rag.evaluation.testset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.evaluation.testset.graph.Node;
import com.smart.rag.evaluation.testset.synthesizers.Persona;
import com.smart.rag.evaluation.util.JsonExtractorUtil;
import com.smart.rag.infrastructure.concurrent.ScopeJoiner;
import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.infrastructure.concurrent.TaskScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Persona 自动生成器（翻译 ragas {@code generate_personas_from_kg}）。
 * <p>
 * 算法：取有 summary + summaryEmbedding 的节点 → 摘要向量 cosine 贪心分组
 * （严格 &gt; 0.75）→ 每组代表取字符最长的 summary → 组数不足按种子重采样补齐 →
 * 每代表一次 PersonaGenerationPrompt 生成（温度 1.0，ragas 同款）。
 * 与 ragas 的唯一差异：ragas 用未归一化 dot（隐含假设嵌入已单位化），此处显式
 * L2 归一后取 cosine（同一意图的数学正确形式）。
 * </p>
 */
public class PersonaGenerator {

    private static final Logger log = LoggerFactory.getLogger(PersonaGenerator.class);

    /** ragas 相似分组阈值（严格大于） */
    static final double GROUP_THRESHOLD = 0.75;

    /** persona 生成温度（ragas generate_personas_from_kg 固定 1.0） */
    private static final double GENERATION_TEMPERATURE = 1.0;

    private static final String PROMPT_TEMPLATE = """
            Using the provided summary, generate a single persona who would likely interact with or benefit from the content. Include a unique name and a concise role description of who they are.

            示例：
            summary："Guide to Digital Marketing explains strategies for engaging audiences across various online platforms."
            → {"name": "Digital Marketing Specialist", "role_description": "Focuses on engaging audiences and growing the brand online."}

            重要约束：name 与 role_description 使用简体中文。

            summary：
            %s

            输出 JSON（不要输出其他内容）：
            {"name": "...", "role_description": "..."}
            """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final ScopedTasks scopedTasks;

    public PersonaGenerator(ChatClient chatClient, ObjectMapper objectMapper,
                            ScopedTasks scopedTasks) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.scopedTasks = scopedTasks;
    }

    /**
     * 从 KG 节点自动生成 persona。
     *
     * @param nodes       KG 节点（取有 summary + summaryEmbedding 者）
     * @param numPersonas 目标 persona 数
     * @param seed        补齐重采样的随机种子（可复现）
     * @throws IllegalStateException 无满足条件的节点
     */
    public List<Persona> generate(List<Node> nodes, int numPersonas, long seed) {
        var candidates = nodes.stream()
                .filter(n -> n.summaryEmbedding() != null
                        && n.summary() != null && !n.summary().isBlank())
                .toList();
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "No nodes that satisfied the given filter. Try changing the filter.");
        }
        int num = Math.min(numPersonas, candidates.size());

        // 贪心分组：i 依序为组头，未访问且与组头 cosine > 0.75 的并入该组（翻译 ragas 分组循环）
        int n = candidates.size();
        boolean[] visited = new boolean[n];
        List<Integer> representatives = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (visited[i]) {
                continue;
            }
            var group = new ArrayList<Integer>();
            group.add(i);
            visited[i] = true;
            for (int j = i + 1; j < n; j++) {
                if (!visited[j] && cosine(candidates.get(i).summaryEmbedding(),
                        candidates.get(j).summaryEmbedding()) > GROUP_THRESHOLD) {
                    group.add(j);
                    visited[j] = true;
                }
            }
            // 组代表：字符最长的 summary（翻译 max(key=len)）
            int rep = group.get(0);
            for (int idx : group) {
                if (candidates.get(idx).summary().length() > candidates.get(rep).summary().length()) {
                    rep = idx;
                }
            }
            representatives.add(rep);
        }

        // 组数不足按种子重采样补齐（翻译 np.random.choice 的补齐分支）
        var topSummaries = representatives.stream()
                .map(idx -> candidates.get(idx).summary())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        var random = new Random(seed);
        while (topSummaries.size() < num) {
            topSummaries.add(topSummaries.get(random.nextInt(topSummaries.size())));
        }

        // 每代表一次生成（温度 1.0，并发），失败不贡献条目
        List<Persona> personas = new ArrayList<>();
        try (TaskScope scope = scopedTasks.open("persona-generation")) {
            for (int i = 0; i < num; i++) {
                String summary = topSummaries.get(i);
                scope.fork("persona-" + i, () -> generateOne(summary));
            }
            @SuppressWarnings("unchecked")
            var generated = (List<Persona>) (List<?>)
                    scope.join(ScopeJoiner.successfulResults(Object.class));
            generated.forEach(p -> {
                if (p != null) {
                    personas.add(p);
                }
            });
        }
        log.info("Personas generated: {} (groups={}, candidates={})",
                personas.size(), representatives.size(), candidates.size());
        return personas;
    }

    /** 单次 persona 生成；失败返回 null（由调用方过滤）。 */
    private Persona generateOne(String summary) {
        try {
            var prompt = PROMPT_TEMPLATE.formatted(summary);
            var response = chatClient.prompt()
                    .user(prompt)
                    .options(ChatOptions.builder().temperature(GENERATION_TEMPERATURE).build())
                    .call()
                    .content();
            if (response == null || response.isBlank()) {
                log.warn("Persona 生成无返回");
                return null;
            }
            var root = objectMapper.readTree(JsonExtractorUtil.extractJson(response));
            String name = root.path("name").asText("");
            String role = root.path("role_description").asText("");
            if (name.isBlank() || role.isBlank()) {
                log.warn("Persona 生成字段缺失");
                return null;
            }
            return new Persona(name.strip(), role.strip());
        } catch (Exception e) {
            log.warn("Persona 生成失败: {}", e);
            return null;
        }
    }

    /** L2 归一后的 cosine（零向量安全返回 0）。 */
    static double cosine(double[] a, double[] b) {
        if (a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
