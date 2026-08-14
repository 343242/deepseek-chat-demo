package com.smart.rag.rag.service;

/** 渲染路径的输出变换类型（设计 §4.2） */
public enum TransformKind {
    /** TXT：检测编码解码后以 UTF-8 text/plain 输出 */
    DETECT_CHARSET,
    /** Markdown：检测编码解码后 CommonMark 渲染 + Jsoup 净化，以 UTF-8 text/html 输出 */
    RENDER_MARKDOWN,
    /** HTML：检测编码解码后 Jsoup 净化，以 UTF-8 text/html 输出 */
    SANITIZE_HTML
}
