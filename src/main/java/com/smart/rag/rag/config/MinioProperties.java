package com.smart.rag.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "spring.minio")
public class MinioProperties {

    /** MinIO 服务地址（服务端内网调用） */
    private String endpoint = "http://localhost:9000";
    /** 浏览器可达地址（presigned URL 签发用，Host 参与签名）；缺省回退 endpoint（dev 同址） */
    private String externalEndpoint;
    /** 签名 region（presign 与服务端 client 须一致），缺省 us-east-1 */
    private String region = "us-east-1";
    /** 访问密钥（必须配置，无默认值） */
    private String accessKey;
    /** 秘密密钥（必须配置，无默认值） */
    private String secretKey;
    /** 默认存储桶名称 */
    private String bucket = "rag-documents";

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getExternalEndpoint() {
        return externalEndpoint != null && !externalEndpoint.isBlank() ? externalEndpoint : endpoint;
    }
    public void setExternalEndpoint(String externalEndpoint) { this.externalEndpoint = externalEndpoint; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
}
