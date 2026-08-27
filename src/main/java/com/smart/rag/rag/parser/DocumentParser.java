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

    /**
     * 解析文档（携带文档身份，design §6.1）。
     * <p>
     * 默认委托两参版本——仅需要文档身份的解析器（如 PDF 图片清单）覆写本方法。
     * 其余解析器零改动。
     */
    default List<Document> parse(Resource resource, String mimeType, ParseContext ctx) {
        return parse(resource, mimeType);
    }

    /**
     * 解析结果载体（design §6.3 中-2）：文档列表 + 图片清单。
     * <p>
     * manifest 不进 {@code Document.metadata}（避免向量库元数据被清单污染的剥离负担），
     * 经本类型化载体透传给 ETL 策略层在短事务内落库。
     */
    record ParsedOutput(List<Document> documents, com.smart.rag.rag.parser.odl.ImageManifest imageManifest) {
        public static ParsedOutput of(List<Document> documents) {
            return new ParsedOutput(documents, new com.smart.rag.rag.parser.odl.ImageManifest(List.of()));
        }
    }

    /**
     * 解析文档并携带图片清单（design §6.2）。默认委托三参 parse（manifest 为空，
     * 兼容非 PDF 链路）。仅 {@code OpenDataLoaderPdfParser} 覆写。
     */
    default ParsedOutput parseWithManifest(Resource resource, String mimeType, ParseContext ctx) {
        return ParsedOutput.of(parse(resource, mimeType, ctx));
    }

    /**
     * 是否为兜底解析器（如 Tika）。兜底解析器不注册到工厂的 MIME 路由表，
     * 仅作为无匹配时的默认解析器。
     */
    default boolean isFallback() {
        return false;
    }
}
