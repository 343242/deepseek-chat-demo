package com.smart.rag.rag.service;

import com.smart.rag.rag.config.DocumentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 规范 MIME 唯一来源（原文件预览与下载设计 §3.2）。
 * <p>
 * 职责：
 * <ul>
 *   <li>定义允许类型的规范 MIME 与扩展名映射</li>
 *   <li>启动期解析并校验 {@code DocumentProperties.allowedMimeTypes}（别名归一化，未知值使启动失败）</li>
 *   <li>把「内容探测结果 + 扩展名」解析为规范 MIME（内容类别与扩展名不一致时返回 null，由调用方拒绝）</li>
 * </ul>
 * 运行时所有 MIME 白名单判断都必须经过本类，不直接读取或拆分配置字符串。
 */
@Component
public class DocumentMimePolicy {

    private static final Logger log = LoggerFactory.getLogger(DocumentMimePolicy.class);

    public static final String MIME_PDF = "application/pdf";
    public static final String MIME_TXT = "text/plain";
    public static final String MIME_MARKDOWN = "text/markdown";
    public static final String MIME_HTML = "text/html";
    public static final String MIME_DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public static final String MIME_PPTX =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    public static final String MIME_XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** 已知别名 → 规范 MIME */
    private static final Map<String, String> ALIASES = Map.of(
            "text/x-markdown", MIME_MARKDOWN
    );

    /** 扩展名 → 规范 MIME（多扩展名共享规范值时逐一列出） */
    private static final Map<String, String> EXTENSION_TO_CANONICAL = buildExtensionMap();

    /** OOXML 规范 MIME → 主文档 part 的 content type（POI 结构确认用） */
    public static final Map<String, String> OOXML_MAIN_PART_CONTENT_TYPE = Map.of(
            MIME_DOCX, "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
            MIME_PPTX, "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml",
            MIME_XLSX, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
    );

    private final DocumentProperties documentProperties;

    /** 启动期一次性解析的允许集合（规范 MIME，别名已归一化） */
    private final Set<String> allowedCanonicalMimes;

    public DocumentMimePolicy(DocumentProperties documentProperties) {
        this.documentProperties = documentProperties;
        this.allowedCanonicalMimes = parseAllowedOrThrow();
        log.info("Document MIME policy initialized: allowed={}", allowedCanonicalMimes);
    }

    /** 配置允许的规范 MIME 集合（只读） */
    public Set<String> allowedCanonicalMimes() {
        return allowedCanonicalMimes;
    }

    /**
     * 判断给定 MIME 是否被允许（先做别名归一化）。
     * 分片上传 init 等需要校验客户端声明值的入口使用。
     */
    public boolean isAllowed(String mimeType) {
        return mimeType != null && allowedCanonicalMimes.contains(normalizeAlias(mimeType.trim()));
    }

    /** 别名归一化（如 {@code text/x-markdown} → {@code text/markdown}）；无别名时原样返回 */
    public String normalizeAlias(String mimeType) {
        return ALIASES.getOrDefault(mimeType, mimeType);
    }

    /**
     * 将「服务端内容探测结果 + 文件扩展名」解析为规范 MIME。
     * <p>
     * 规则（设计 §3.2）：内容类别必须与扩展名一致——PDF 探测必须配 {@code .pdf}；
     * 文本类探测按扩展名细分 txt/md/html；OOXML 探测必须与扩展名指向同一规范值。
     *
     * @param probedMime 服务端内容探测得到的候选 MIME（Tika 输出，含参数时以分号前部分为准）
     * @param fileName   原始文件名（用于扩展名判定）
     * @return 规范 MIME；内容与扩展名不一致或类型未知时返回 null（调用方应拒绝上传）
     */
    public String canonicalForProbe(String probedMime, String fileName) {
        if (probedMime == null || fileName == null) {
            return null;
        }
        String probed = probedMime.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        String extension = extensionOf(fileName);
        if (extension == null) {
            return null;
        }
        String byExtension = EXTENSION_TO_CANONICAL.get(extension);

        if (MIME_PDF.equals(probed)) {
            return MIME_PDF.equals(byExtension) ? MIME_PDF : null;
        }
        if (probed.startsWith("text/")) {
            // 文本类内容：规范值由扩展名细分（txt/md/html），其余扩展名一律不一致
            return byExtension != null && isTextCanonical(byExtension) ? byExtension : null;
        }
        if (OOXML_MAIN_PART_CONTENT_TYPE.containsKey(probed)) {
            // OOXML：探测到的子类型必须与扩展名指向同一规范值
            return probed.equals(byExtension) ? probed : null;
        }
        return null;
    }

    /** 是否为 OOXML 规范 MIME */
    public boolean isOoxml(String mimeType) {
        return OOXML_MAIN_PART_CONTENT_TYPE.containsKey(mimeType);
    }

    /** 文件扩展名（小写、含点）；无扩展名返回 null */
    static String extensionOf(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot);
    }

    private static boolean isTextCanonical(String canonical) {
        return MIME_TXT.equals(canonical) || MIME_MARKDOWN.equals(canonical) || MIME_HTML.equals(canonical);
    }

    /**
     * 启动期解析配置：trim、过滤空段、别名归一化，未知值直接抛异常使启动失败。
     */
    private Set<String> parseAllowedOrThrow() {
        Set<String> allowed = Arrays.stream(documentProperties.getAllowedMimeTypes().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(this::normalizeAlias)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        for (String mime : allowed) {
            if (!EXTENSION_TO_CANONICAL.containsValue(mime)) {
                throw new IllegalStateException(
                        "app.document.allowed-mime-types 含未知或非规范值: " + mime
                                + "，允许的规范值见 DocumentMimePolicy");
            }
        }
        if (allowed.isEmpty()) {
            throw new IllegalStateException("app.document.allowed-mime-types 不能为空");
        }
        return Set.copyOf(allowed);
    }

    private static Map<String, String> buildExtensionMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(".pdf", MIME_PDF);
        map.put(".txt", MIME_TXT);
        map.put(".md", MIME_MARKDOWN);
        map.put(".markdown", MIME_MARKDOWN);
        map.put(".html", MIME_HTML);
        map.put(".htm", MIME_HTML);
        map.put(".docx", MIME_DOCX);
        map.put(".pptx", MIME_PPTX);
        map.put(".xlsx", MIME_XLSX);
        return Map.copyOf(map);
    }
}
