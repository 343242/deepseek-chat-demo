package com.smart.rag.agent.config;

import com.smart.rag.mode.AgentIntent;
import jakarta.annotation.PostConstruct;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Agent 意图 prompt 加载器 — 从 classpath 加载按意图索引的 XML prompt 文件。
 * <p>
 * <b>与 {@link com.smart.rag.chat.service.PromptLoaderService} 的边界</b>：
 * PromptLoaderService 是<b>模型维度</b>（按 modelId 索引，含 Redis 缓存和 UI 暴露），
 * 服务于按模型选择 system prompt 的场景。本加载器是<b>意图维度</b>（按 {@link AgentIntent}
 * 索引），服务于 Agent 模式按意图选择 ReAct 增强 prompt（自省/原子决策/中间答案引导）的场景。
 * 两者职责正交，互不污染——Agent 的 4 段 prompt 不会进入 PromptLoaderService 的 model 列表或 Redis。
 * <p>
 * <b>加载位置</b>：{@code classpath:static/prompt/agent/*.xml}（子目录，与 {@code static/prompt/*.xml}
 * 分层，现有 PromptLoaderService 的 glob {@code *.xml} 不扫子目录，不会被误扫）。
 * <p>
 * <b>XML 约定</b>：每个文件的根元素 {@code <prompt model="DIRECT_ANSWER|RETRIEVAL|DEEP_RETRIEVAL|GENERAL_TOOL">}，
 * {@code model} 属性值必须与 {@link AgentIntent} 枚举名严格一致（valueOf 解析）。
 * 加载后保留 raw XML 原文作为 prompt 文本（与 PromptLoaderService 的 toSystemPrompt 语义一致，
 * 让 LLM 通过 XML 标签语义理解结构化指令）。
 * <p>
 * <b>不进 Redis、不暴露 UI</b>：Agent prompt 是静态配置（无运行时编辑需求），纯内存 Map，
 * 启动时加载一次。变更需重启或重新部署。
 */
@Service
public class AgentPromptLoader {

    private static final Logger log = LoggerFactory.getLogger(AgentPromptLoader.class);

    /** Agent prompt 文件扫描位置（子目录，与 PromptLoaderService 的 static/prompt/*.xml 隔离） */
    private static final String AGENT_PROMPT_LOCATION = "classpath:static/prompt/agent/*.xml";

    /** intent → raw XML prompt 文本（不可变，启动后只读） */
    private volatile Map<AgentIntent, String> templates = Collections.emptyMap();

    @PostConstruct
    void load() {
        Map<AgentIntent, String> loaded = new EnumMap<>(AgentIntent.class);
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(AGENT_PROMPT_LOCATION);

            for (Resource resource : resources) {
                try (InputStream is = resource.getInputStream()) {
                    String rawXml = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    String modelName = parseModelAttribute(rawXml);

                    if (modelName == null || modelName.isBlank()) {
                        log.warn("Agent prompt file missing 'model' attribute: {}", resource.getFilename());
                        continue;
                    }

                    AgentIntent intent;
                    try {
                        intent = AgentIntent.valueOf(modelName);
                    } catch (IllegalArgumentException e) {
                        log.warn("Agent prompt file '{}' has unknown intent '{}', expected one of DIRECT_ANSWER/RETRIEVAL/DEEP_RETRIEVAL/GENERAL_TOOL",
                            resource.getFilename(), modelName);
                        continue;
                    }

                    loaded.put(intent, stripWrapper(rawXml));
                    log.info("Loaded agent prompt for intent: {} (from {})", intent, resource.getFilename());
                }
            }

            this.templates = Collections.unmodifiableMap(loaded);
            log.info("Agent prompts loaded: {} intents configured", loaded.size());

        } catch (Exception e) {
            log.error("Failed to load agent prompts, all intents will fall back to hardcoded default", e);
            // 保持空 map，调用方走 AgentModeStrategy 的兜底文本
        }
    }

    /**
     * 按意图取 prompt 文本（raw XML）。
     *
     * @param intent Agent 意图
     * @return prompt 文本；未配置该意图时返回 null（由 AgentModeStrategy 用硬编码兜底）
     */
    public @Nullable String getPrompt(AgentIntent intent) {
        return templates.get(intent);
    }

    /**
     * 剥离 XML 文档的元信息外壳，只保留 LLM 需要的内部结构化指令内容。
     * <p>
     * 清理对象（纯噪音，LLM 不解析 XML）：
     * <ol>
     *   <li>{@code <?xml ...?>} 声明 — 给 XML 解析器的元信息，LLM 无用</li>
     *   <li>{@code <prompt version="1.0" model="...">} 根元素开标签 —
     *       version/model 属性是加载器识别意图用的，LLM 无需感知</li>
     *   <li>{@code </prompt>} 根元素闭标签</li>
     * </ol>
     * <p>
     * <b>必须保留</b>：所有内部结构标签（{@code <role>}/{@code <workflow>}/
     * {@code <decision_rules>}/{@code <example>} 等）——它们是 Prompt Engineering
     * 的核心语义锚点（Anthropic 官方：LLM 对 XML 标签有 fine-tune 偏好）。
     * <p>
     * <b>为什么不用 DOM 解析</b>：{@code Element.getTextContent()} 会 flatten 所有
     * 内部标签变成纯文本，丧失结构信号；只能用字符串/正则处理。
     * <p>
     * <b>正则安全性</b>：{@code <prompt>} 标签属性值（version/model）不含 {@code >}，
     * {@code <prompt[^>]*>} 非贪婪到首个 {@code >} 安全匹配。
     *
     * @param rawXml 原始 XML 文档文本
     * @return 清理后的 prompt 正文（首尾已 trim）
     */
    static String stripWrapper(String rawXml) {
        String s = rawXml;
        // 1. 剥离首行 XML 声明 <?xml ...?>（含其后空白/换行）
        s = s.replaceFirst("(?s)^<\\?xml[^>]*\\?>\\s*", "");
        // 2. 剥离 <prompt ...> 根元素开标签（首次出现）
        s = s.replaceFirst("<prompt[^>]*>", "");
        // 3. 剥离 </prompt> 根元素闭标签（末尾，含前置空白）
        s = s.replaceFirst("(?s)\\s*</prompt>\\s*$", "");
        return s.trim();
    }

    /**
     * 解析 XML 根元素的 model 属性值。
     * <p>
     * 仅取根元素属性，不解析正文（正文由 LLM 直接读取 raw XML）。
     * XXE 防护与 PromptLoaderServiceImpl.parseXml 一致。
     */
    private static String parseModelAttribute(String rawXml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

        var builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(
            new ByteArrayInputStream(rawXml.getBytes(StandardCharsets.UTF_8))));
        Element root = doc.getDocumentElement();
        return root.getAttribute("model");
    }
}
