package com.smart.rag.evaluation.testset.transforms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.evaluation.testset.graph.Node;
import com.smart.rag.evaluation.util.JsonExtractorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 节点问题潜力过滤器（翻译 ragas {@code CustomNodeFilter} + QuestionPotentialPrompt）。
 * <p>
 * 对 chunk 内容按五档 rubric 打 1-5 分（相对 document_summary 的问题生成潜力），
 * {@code score <= minScore}（默认 2）的节点从 KG 剔除。
 * 语义决策：ragas 原版对 CHUNK 取父文档 summary 作 document_summary，但 prechunked
 * 管线没有文档节点、summary 恒为空 → 过滤被跳过（prechunked 路径下是死代码）；
 * 本实现取 chunk 自身 summary（SummaryExtractor 产出），让过滤真正生效。
 * 摘要为空或评分调用失败 → 保留节点（不因过滤误删）。
 * </p>
 */
public class NodePotentialFilter {

    private static final Logger log = LoggerFactory.getLogger(NodePotentialFilter.class);

    /** ragas DEFAULT_RUBRICS 逐字移植（五档评分描述） */
    static final Map<String, String> DEFAULT_RUBRICS;

    static {
        Map<String, String> rubrics = new LinkedHashMap<>();
        rubrics.put("score1_description",
                "The page content is irrelevant or does not align with the main themes or topics of the document summary.");
        rubrics.put("score2_description",
                "The page content partially aligns with the document summary, but it includes unrelated details or lacks critical information related to the document's main themes.");
        rubrics.put("score3_description",
                "The page content generally reflects the document summary but may miss key details or lack depth in addressing the main themes.");
        rubrics.put("score4_description",
                "The page content aligns well with the document summary, covering the main themes and topics with minor gaps or minimal unrelated information.");
        rubrics.put("score5_description",
                "The page content is highly relevant, accurate, and directly reflects the main themes of the document summary, covering all important details and adding depth to the understanding of the document's topics.");
        DEFAULT_RUBRICS = java.util.Collections.unmodifiableMap(rubrics);
    }

    private static final String PROMPT_TEMPLATE = """
            Given a document summary and node content, score the content of the node in 1 to 5 range.

            rubrics:
            score1_description: "%s"
            score2_description: "%s"
            score3_description: "%s"
            score4_description: "%s"
            score5_description: "%s"

            文档摘要（document_summary）：
            %s

            节点内容（node_content）：
            %s

            输出 JSON（不要输出其他内容）：
            {"score": 3}
            """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final int minScore;

    public NodePotentialFilter(ChatClient chatClient, ObjectMapper objectMapper, int minScore) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.minScore = minScore;
    }

    /**
     * 判定节点是否应被剔除。
     *
     * @return true = 剔除（score ≤ minScore）；摘要为空/评分失败 → false（保留）
     */
    public boolean shouldRemove(Node node) {
        if (node.summary() == null || node.summary().isBlank()) {
            // ragas 原版行为：无 summary 跳过过滤（保留节点）
            log.warn("Node {} does not have a summary. Skipping filtering.", node.id());
            return false;
        }
        var prompt = PROMPT_TEMPLATE.formatted(
                DEFAULT_RUBRICS.get("score1_description"),
                DEFAULT_RUBRICS.get("score2_description"),
                DEFAULT_RUBRICS.get("score3_description"),
                DEFAULT_RUBRICS.get("score4_description"),
                DEFAULT_RUBRICS.get("score5_description"),
                node.summary(),
                node.pageContent());
        var response = chatClient.prompt().user(prompt).call().content();
        if (response == null || response.isBlank()) {
            log.warn("问题潜力评分无返回: chunk={}", node.id());
            return false;
        }
        try {
            var root = objectMapper.readTree(JsonExtractorUtil.extractJson(response));
            int score = root.path("score").asInt(0);
            return score <= minScore;
        } catch (Exception e) {
            log.warn("问题潜力评分解析失败: chunk={}", node.id(), e);
            return false;
        }
    }
}
