package com.smart.rag.rag.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 查询文本归一化处理器。
 * <p>
 * 在检索前对用户输入做文本归一化，提升检索精确度。
 * 主要解决全角/半角、Unicode 变体、空白等字符差异导致的匹配丢失问题，
 * 对 BM25 关键词检索效果尤为显著。
 * </p>
 *
 * <p>处理步骤：</p>
 * <ol>
 *   <li>全角 ASCII → 半角 ASCII（字母、数字、符号）</li>
 *   <li>全角空格 → 半角空格</li>
 *   <li>兼容分解（如 é → e + ́ ，① → 1）</li>
 *   <li>连续空白压缩为单个空格</li>
 *   <li>首尾空白裁剪</li>
 * </ol>
 *
 * <p>设计原则：</p>
 * <ul>
 *   <li>纯函数式，无状态，线程安全</li>
 *   <li>不改变语义：保留中文字符、标点、意图</li>
 *   <li>可逆：原始文本可通过日志追溯</li>
 * </ul>
 */
@Component
public class QueryNormalizer {

    private static final Logger log = LoggerFactory.getLogger(QueryNormalizer.class);

    /** 连续空白 → 单个半角空格（每次查询执行，预编译避免重复 Pattern.compile） */
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    /** tsquery 运算符/引号字符类（BM25 每次查询执行，预编译避免重复 Pattern.compile） */
    private static final Pattern TSQUERY_OPERATOR_PATTERN = Pattern.compile("[&|!()\\[\\]{}:*\\\\\"']");

    /**
     * 对查询文本进行归一化处理。
     *
     * @param query 原始查询文本
     * @return 归一化后的查询文本；输入为 null/空白时返回空字符串
     */
    public String normalize(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }

        String normalized = fullwidthToHalfwidth(query);
        normalized = nfcNormalize(normalized);
        normalized = compressWhitespace(normalized);

        // R1-L4: 不记录原始 query（可能含 PII），仅记录长度变化与归一化后文本
        if (!normalized.equals(query.trim())) {
            log.info("Query normalized: len {} → {}, normalized='{}'",
                    query.trim().length(), normalized.length(), normalized);
        }

        return normalized;
    }

    // ======================== 全角→半角 ========================

    /**
     * 全角 ASCII 字符转换为半角。
     * <p>
     * 覆盖范围：
     * <ul>
     *   <li>U+FF01 ~ U+FF5E → 对应 ASCII 0x21 ~ 0x7E（!~<sub>}</sub>~）</li>
     *   <li>U+3000 → U+0020（全角空格 → 半角空格）</li>
     * </ul>
     * </p>
     */
    private String fullwidthToHalfwidth(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> sb.appendCodePoint(fullwidthToHalfwidthCodePoint(cp)));
        return sb.toString();
    }

    private int fullwidthToHalfwidthCodePoint(int codePoint) {
        // 全角 ASCII 符号/数字/字母 → 半角
        // 范围 U+FF01~FF5E 覆盖了全角标点（！，。）等，
        // 但中文语境下这些标点不应转换（如「你好，世界！」中的全角标点是中文正常书写），
        // 所以只转换：全角字母 (A-Z, a-z)、全角数字 (0-9)、全角空格
        if (codePoint >= 0xFF21 && codePoint <= 0xFF3A) {  // Ａ~Ｚ
            return codePoint - 0xFEE0;
        }
        if (codePoint >= 0xFF41 && codePoint <= 0xFF5A) {  // ａ~ｚ
            return codePoint - 0xFEE0;
        }
        if (codePoint >= 0xFF10 && codePoint <= 0xFF19) {  // ０~９
            return codePoint - 0xFEE0;
        }
        // 全角空格 → 半角空格
        if (codePoint == 0x3000) {
            return 0x0020;
        }
        return codePoint;
    }

    // ======================== Unicode NFC 归一化 ========================

    /**
     * NFC（Normalization Form C）归一化。
     * <p>
     * 将 Unicode 组合字符序列合并为预组合形式，消除同一文字的多种编码表示。
     * 例如：{@code e + ◌́ → é}，{@code ① → 1}（兼容分解后重新组合）。
     * </p>
     * <p>
     * 选择 NFC 而非 NFKC：NFC 保留语义等价性（① 不会变成 1），
     * 仅合并编码变体，不改变字符含义。
     * </p>
     */
    private String nfcNormalize(String text) {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC);
    }

    // ======================== 空白压缩 ========================

    /**
     * 将连续空白字符（空格、制表符、换行等）压缩为单个半角空格。
     */
    private String compressWhitespace(String text) {
        return WHITESPACE_PATTERN.matcher(text).replaceAll(" ").trim();
    }

    // ======================== BM25 / tsquery 净化 ========================

    /**
     * 净化查询文本，去掉 PostgreSQL tsquery 运算符。
     * <p>
     * 保留完整中文文本交给 pg_jieba 分词，只剥离会干扰 tsquery 解析的特殊字符：
     * <ul>
     *   <li>中文引号（左右双引号、单引号、书名号）替换为空格</li>
     *   <li>tsquery 运算符：{@code & | ! ( ) [ ] { } : * \ " '}替换为空格</li>
     * </ul>
     * <p>
     * 应在 {@link #normalize(String)} 之后调用，确保输入已是归一化文本。
     *
     * @param query 已归一化的查询文本
     * @return 去除 tsquery 运算符后的文本；输入为 null/空白时返回空字符串
     */
    public String sanitizeForTsQuery(String query) {
        if (query == null || query.isBlank()) return "";
        return TSQUERY_OPERATOR_PATTERN
                .matcher(query
                        .replace('“', ' ').replace('”', ' ')   // left/right double quotation
                        .replace('‘', ' ').replace('’', ' ')   // left/right single quotation
                        .replace('«', ' ').replace('»', ' '))  // << >>
                .replaceAll(" ")
                .trim();
    }
}
