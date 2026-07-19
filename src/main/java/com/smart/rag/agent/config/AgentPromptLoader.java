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

                    loaded.put(intent, rawXml.trim());
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
