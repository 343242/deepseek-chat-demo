package com.smart.rag.evaluation.testset.transforms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.evaluation.testset.graph.Node;
import com.smart.rag.evaluation.util.JsonExtractorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 主题抽取器（翻译 ragas {@code ThemesExtractor} + ThemesAndConceptsExtractorPrompt）。
 * <p>
 * 提示词保留 ragas 英文原文语义（max_num=10），追加中文输出约束——主题用于
 * persona×theme 匹配与出题，实体名多为中文，主题以中文产出更利于后续匹配。
 * LLM 调用遵循项目范式：content() → JsonExtractorUtil → Jackson。
 * </p>
 */
public class ThemesExtractor {

    private static final Logger log = LoggerFactory.getLogger(ThemesExtractor.class);

    private static final String PROMPT_TEMPLATE = """
            Extract the main themes and concepts from the given text. Return at most 10.

            重要约束：主题和概念必须使用简体中文表达；每个主题 2~8 个字，不要句子。

            文本：
            %s

            输出 JSON（不要输出其他内容）：
            {"themes": ["主题1", "主题2"]}
            """;

    /** 输入截断上限：ragas 输入模型带 max_num 约束且分块上限 32k token；chunk 来自向量库已远小于此，仅防御超长输入。 */
    private static final int MAX_INPUT_LENGTH = 8000;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public ThemesExtractor(ChatClient chatClient, ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 抽取节点主题。LLM 返回为空/解析失败时返回空列表（单 chunk 失败不阻断批次，
     * 由编排器在 fork 内捕获异常，此处只兜解析层）。
     */
    public List<String> extract(Node node) {
        var prompt = PROMPT_TEMPLATE.formatted(truncate(node.pageContent()));
        var response = chatClient.prompt().user(prompt).call().content();
        if (response == null || response.isBlank()) {
            log.warn("Themes 抽取无返回: chunk={}", node.id());
            return List.of();
        }
        try {
            var root = objectMapper.readTree(JsonExtractorUtil.extractJson(response));
            var themes = new ArrayList<String>();
            root.path("themes").forEach(themeNode -> {
                if (themeNode.isTextual() && !themeNode.asText().isBlank()) {
                    themes.add(themeNode.asText().strip());
                }
            });
            return themes.stream().distinct().toList();
        } catch (Exception e) {
            log.warn("Themes 抽取解析失败: chunk={}", node.id(), e);
            return List.of();
        }
    }

    /** ragas 输入模型带 max_num 约束且分块上限 32k token；chunk 来自向量库已远小于此，仅截断防御超长输入。 */
    private static String truncate(String content) {
        return content.length() <= MAX_INPUT_LENGTH ? content : content.substring(0, MAX_INPUT_LENGTH);
    }
}
