package com.demo.chat.chat.service.impl;

import com.demo.chat.chat.dto.PromptTemplate;
import com.demo.chat.chat.service.PromptLoaderService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * XML 系统提示词加载服务
 * <p>
 * 从 classpath:static/prompt/*.xml 加载结构化提示词模板，
 * 启动时将 XML 原文存入 Redis（TTL 1 天），直接作为 system prompt 发给大模型。
 * <p>
 * 大模型通过 XML 标签语义理解角色、规则、约束等结构化指令，
 * 比平铺的 Markdown 文本具有更好的指令遵循效果。
 */
@Service
public class PromptLoaderServiceImpl implements PromptLoaderService {

    private static final Logger log = LoggerFactory.getLogger(PromptLoaderServiceImpl.class);
    private static final String PROMPT_LOCATION = "classpath:static/prompt/*.xml";
    private static final String REDIS_KEY_PREFIX = "prompt:xml:";
    private static final Duration REDIS_TTL = Duration.ofDays(1);

    private final StringRedisTemplate redisTemplate;
    private volatile Map<String, PromptTemplate> templates = new ConcurrentHashMap<>();
    private volatile PromptTemplate defaultTemplate;

    public PromptLoaderServiceImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    @Override
    public void loadPrompts() {
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(PROMPT_LOCATION);

            for (Resource resource : resources) {
                try (InputStream is = resource.getInputStream()) {
                    String rawXml = new String(is.readAllBytes(), StandardCharsets.UTF_8);

                    PromptTemplate template = parseXml(rawXml);
                    if (template != null) {
                        templates.put(template.model(), template);

                        // 仅在 Redis 中不存在时写入，避免每次启动都写 Redis
                        if (Boolean.FALSE.equals(redisTemplate.hasKey(REDIS_KEY_PREFIX + template.model()))) {
                            redisTemplate.opsForValue().set(
                                    REDIS_KEY_PREFIX + template.model(), rawXml, REDIS_TTL);
                            log.info("Loaded prompt template for model: {} → Redis (TTL=1d)", template.model());
                        } else {
                            // 刷新 TTL
                            redisTemplate.expire(REDIS_KEY_PREFIX + template.model(), REDIS_TTL);
                            log.info("Loaded prompt template for model: {} → Redis (already cached, TTL refreshed)", template.model());
                        }

                        if ("default".equals(template.model())) {
                            defaultTemplate = template;
                        }
                    }
                }
            }

            log.info("Prompt templates loaded: {} models, default={}",
                    templates.size(), defaultTemplate != null);

        } catch (Exception e) {
            log.error("Failed to load prompt templates", e);
        }
    }

    @Override
    public String getPrompt(String modelId) {
        PromptTemplate template = templates.get(modelId);
        if (template == null) {
            template = defaultTemplate;
        }
        return template != null ? template.toSystemPrompt() : null;
    }

    @Override
    public String getPromptFromRedis(String modelId) {
        return redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + modelId);
    }

    @Override
    public PromptTemplate getTemplate(String modelId) {
        return templates.getOrDefault(modelId, defaultTemplate);
    }

    @Override
    public List<String> getAvailableModels() {
        return Collections.unmodifiableList(new ArrayList<>(templates.keySet()));
    }

    @Override
    public void reload() {
        Map<String, PromptTemplate> newTemplates = new ConcurrentHashMap<>();
        PromptTemplate newDefault = null;

        try {
            var resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(PROMPT_LOCATION);

            for (Resource resource : resources) {
                try (InputStream is = resource.getInputStream()) {
                    String rawXml = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    PromptTemplate template = parseXml(rawXml);
                    if (template != null) {
                        newTemplates.put(template.model(), template);
                        if ("default".equals(template.model())) {
                            newDefault = template;
                        }
                    }
                }
            }

            this.templates = newTemplates;
            this.defaultTemplate = newDefault;

            for (var entry : newTemplates.entrySet()) {
                redisTemplate.opsForValue().set(
                        REDIS_KEY_PREFIX + entry.getKey(),
                        entry.getValue().toSystemPrompt(),
                        REDIS_TTL);
            }

            log.info("Prompt templates reloaded: {} models, Redis refreshed", templates.size());
        } catch (Exception e) {
            log.error("Failed to reload prompt templates, keeping existing", e);
        }
    }

    private PromptTemplate parseXml(String rawXml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            var builder = factory.newDocumentBuilder();
            Document doc = builder.parse(
                    new java.io.ByteArrayInputStream(rawXml.getBytes(StandardCharsets.UTF_8)));
            Element root = doc.getDocumentElement();

            String model = root.getAttribute("model");
            String role = getTextContent(root, "role");
            List<String> rules = getTextList(root, "rules", "rule");
            List<String> constraints = getTextList(root, "constraints", "constraint");
            List<String> capabilities = getTextList(root, "capabilities", "capability");

            return new PromptTemplate(model, rawXml.trim(), role, rules, constraints, capabilities);

        } catch (Exception e) {
            log.error("Failed to parse XML prompt", e);
            return null;
        }
    }

    private String getTextContent(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent().trim();
        }
        return null;
    }

    private List<String> getTextList(Element parent, String wrapperTag, String itemTag) {
        List<String> result = new ArrayList<>();
        NodeList wrappers = parent.getElementsByTagName(wrapperTag);
        if (wrappers.getLength() > 0) {
            Element wrapper = (Element) wrappers.item(0);
            NodeList items = wrapper.getElementsByTagName(itemTag);
            for (int i = 0; i < items.getLength(); i++) {
                String text = items.item(i).getTextContent().trim();
                if (!text.isEmpty()) {
                    result.add(text);
                }
            }
        }
        return result;
    }
}
