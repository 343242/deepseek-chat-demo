package com.demo.chat.rag.parser;

import org.springframework.core.io.ByteArrayResource;

/**
 * 保留文件名的 ByteArrayResource 包装。
 * <p>
 * {@link ByteArrayResource#getFilename()} 默认返回 null，
 * 在编码检测转码后需要保留原始文件名传给下游解析器。
 */
class NamedByteArrayResource extends ByteArrayResource {

    private final String filename;

    NamedByteArrayResource(byte[] bytes, String filename) {
        super(bytes);
        this.filename = filename;
    }

    @Override
    public String getFilename() {
        return filename;
    }
}
