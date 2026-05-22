package com.smart.rag.rag.parser;

import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;

import java.util.List;

/**
 * 文档解析策略接口
 * <p>
 * 每种文档格式的解析器实现此接口，通过 {@link DocumentParserFactory} 按 MIME 类型路由。
 * 新增格式只需新增实现类 + 注册到工厂，符合 OCP。
 * </p>
 */
public interface DocumentParser {

    /**
     * 支持的 MIME 类型列表（如 application/pdf）
     */
    List<String> supportedMimeTypes();

    /**
     * 解析文档为 Spring AI Document 列表
     *
     * @param resource 文件资源
     * @param mimeType 实际 MIME 类型
     * @return 解析后的文档列表
     */
    List<Document> parse(Resource resource, String mimeType);
}
