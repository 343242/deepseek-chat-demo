package com.demo.chat.rag.parser;

import com.demo.chat.rag.service.impl.MinioFileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 文档解析器工厂
 * <p>
 * 根据文件的 MIME 类型选择合适的 {@link DocumentParser} 实现。
 * 遵循开闭原则：新增解析器只需实现 DocumentParser 并注册为 Spring Bean，
 * 工厂自动发现，无需修改已有代码。
 * </p>
 */
@Slf4j
@Component
public class DocumentParserFactory {

    /** MIME 类型 → 解析器的路由表 */
    private final Map<String, DocumentParser> parserMap;

    /** 默认解析器（Tika 兜底） */
    private final DocumentParser defaultParser;

    /**
     * Spring 自动注入所有 DocumentParser 实现，构建路由表。
     * 优先匹配专用解析器，无匹配时降级到 Tika。
     */
    public DocumentParserFactory(List<DocumentParser> parsers, TikaDocumentParser tikaParser) {
        this.defaultParser = tikaParser;
        // 构建 MIME → Parser 映射
        java.util.Map<String, DocumentParser> map = new java.util.HashMap<>();
        for (DocumentParser parser : parsers) {
            if (parser instanceof TikaDocumentParser) {
                continue; // Tika 作为 default，不注册到 map 中
            }
            for (String mime : parser.supportedMimeTypes()) {
                map.put(mime, parser);
            }
        }
        this.parserMap = map;
        log.info("DocumentParserFactory initialized: {} specific parsers registered, default: TikaDocumentParser",
                map.size());
    }

    /**
     * 根据 MIME 类型获取对应的解析器
     *
     * @param mimeType MIME 类型
     * @return 匹配的解析器，无匹配时返回 Tika 兜底
     */
    public DocumentParser getParser(String mimeType) {
        return Optional.ofNullable(parserMap.get(mimeType))
                .orElse(defaultParser);
    }
}
