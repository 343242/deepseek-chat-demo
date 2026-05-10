package com.demo.chat.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.document")
public class DocumentProperties {

    /**
     * 最大文件大小（Spring 格式，如 50MB）
     */
    private String maxFileSize = "50MB";

    /**
     * 允许的 MIME 类型，逗号分隔
     */
    private String allowedMimeTypes = "application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.openxmlformats-officedocument.presentationml.presentation,text/plain,text/markdown,text/html";

    /**
     * 文档分块大小（tokens）
     */
    private int chunkSize = 800;

    /**
     * 分块重叠大小（tokens）
     */
    private int chunkOverlap = 200;
}
