package com.demo.chat.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "spring.minio")
public class MinioProperties {

    /**
     * MinIO 服务地址
     */
    private String endpoint = "http://localhost:9000";

    /**
     * 访问密钥
     */
    private String accessKey = "minioadmin";

    /**
     * 秘密密钥
     */
    private String secretKey = "minioadmin123";

    /**
     * 默认存储桶名称
     */
    private String bucket = "rag-documents";
}
