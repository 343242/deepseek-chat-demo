package com.demo.chat.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "spring.minio")
public class MinioProperties {

    /** MinIO 服务地址 */
    private String endpoint = "http://localhost:9000";
    /** 访问密钥（必须配置，无默认值） */
    private String accessKey;
    /** 秘密密钥（必须配置，无默认值） */
    private String secretKey;
    /** 默认存储桶名称 */
    private String bucket = "rag-documents";

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
}
