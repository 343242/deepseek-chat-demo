package com.smart.rag.rag.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 文档解析器工厂。
 * 根据文件的 MIME 类型选择合适的 {@link DocumentParser} 实现。
 * 新增解析器只需实现 DocumentParser 并注册为 Spring Bean，无需修改已有代码。
 */
@Component
public class DocumentParserFactory {

    private static final Logger log = LoggerFactory.getLogger(DocumentParserFactory.class);

    private final Map<String, DocumentParser> parserMap;
    private final DocumentParser defaultParser;

    public DocumentParserFactory(List<DocumentParser> parsers, TikaDocumentParser tikaParser) {
        this.defaultParser = tikaParser;
        Map<String, DocumentParser> map = new HashMap<>();
        for (DocumentParser parser : parsers) {
            if (parser instanceof TikaDocumentParser) {
                continue;
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
     * 根据 MIME 类型获取对应的解析器，无匹配时返回 Tika 兜底。
     */
    public DocumentParser getParser(String mimeType) {
        return Optional.ofNullable(parserMap.get(mimeType)).orElse(defaultParser);
    }
}
