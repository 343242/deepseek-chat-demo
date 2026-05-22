package com.smart.rag.rag.parser;

/**
 * 文档解析异常 — 文件级不可恢复错误时抛出。
 * <p>
 * 使用场景：
 * <ul>
 *   <li>文件损坏、格式不合法</li>
 *   <li>文件加密 / 密码保护</li>
 *   <li>IO 错误（文件不存在、权限不足）</li>
 * </ul>
 * <p>
 * 不使用的场景（静默处理）：
 * <ul>
 *   <li>空 Sheet / 空 Slide → 跳过，返回空 List</li>
 *   <li>个别 Shape 解析失败 → 跳过该 Shape，记录 warn 日志</li>
 *   <li>公式无缓存值 → 空字符串</li>
 * </ul>
 */
public class DocumentParseException extends RuntimeException {

    private final String fileName;
    private final String parserName;

    public DocumentParseException(String fileName, String parserName, String message, Throwable cause) {
        super(String.format("[%s] Failed to parse '%s': %s", parserName, fileName, message), cause);
        this.fileName = fileName;
        this.parserName = parserName;
    }

    public String getFileName() {
        return fileName;
    }

    public String getParserName() {
        return parserName;
    }
}
