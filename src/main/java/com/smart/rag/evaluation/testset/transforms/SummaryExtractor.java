package com.smart.rag.evaluation.testset.transforms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.evaluation.testset.graph.Node;
import com.smart.rag.evaluation.util.JsonExtractorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

/**
 * 摘要抽取器（翻译 ragas {@code SummaryExtractor} + SummaryExtractorPrompt）。
 * <p>
 * 逐 chunk 生成"少于 10 句"的摘要，作为摘要向量（相似边）与问题潜力过滤
 * （document_summary）的输入。提示词保留 ragas 英文指令与 few-shot 原文，
 * 追加中文输出约束（摘要服务于中文出题链路）。
 * </p>
 */
public class SummaryExtractor {

    private static final Logger log = LoggerFactory.getLogger(SummaryExtractor.class);

    private static final String PROMPT_TEMPLATE = """
            Summarize the given text in less than 10 sentences.

            示例：
            文本："Artificial intelligence\n\nArtificial intelligence is transforming various industries by automating tasks that previously required human intelligence. From healthcare to finance, AI is being used to analyze vast amounts of data quickly and accurately. This technology is also driving innovations in areas like self-driving cars and personalized recommendations."
            → {"text": "AI is revolutionizing industries by automating tasks, analyzing data, and driving innovations like self-driving cars and personalized recommendations."}

            重要约束：使用简体中文总结。

            文本：
            %s

            输出 JSON（不要输出其他内容）：
            {"text": "..."}
            """;

    /** 输入截断上限：ragas 输入分块上限 32k token；chunk 来自向量库已远小于此，仅防御超长输入。 */
    private static final int MAX_INPUT_LENGTH = 8000;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public SummaryExtractor(ChatClient chatClient, ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 抽取节点摘要。LLM 返回为空/解析失败时返回空串（单 chunk 失败不阻断批次，
     * 该节点被潜力过滤跳过、不参与相似边）。
     */
    public String extract(Node node) {
        var prompt = PROMPT_TEMPLATE.formatted(truncate(node.pageContent()));
        var response = chatClient.prompt().user(prompt).call().content();
        if (response == null || response.isBlank()) {
            log.warn("Summary 抽取无返回: chunk={}", node.id());
            return "";
        }
        try {
            var root = objectMapper.readTree(JsonExtractorUtil.extractJson(response));
            var text = root.path("text").asText("");
            return text.strip();
        } catch (Exception e) {
            log.warn("Summary 抽取解析失败: chunk={}", node.id(), e);
            return "";
        }
    }

    private static String truncate(String content) {
        return content.length() <= MAX_INPUT_LENGTH ? content : content.substring(0, MAX_INPUT_LENGTH);
    }
}
