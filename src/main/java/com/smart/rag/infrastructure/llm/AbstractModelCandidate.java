package com.smart.rag.infrastructure.llm;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;

import java.util.Map;

/**
 * 模型候选抽象基类——实现 sealed interface，提供 YAML 绑定支持
 * <p>
 * 保留 POJO getter/setter 供 Spring Boot {@code @ConfigurationProperties} 绑定。
 * 子类（{@code ChatCandidate}、{@code EmbeddingCandidate}、{@code RerankCandidate}）
 * 添加能力特定字段并覆写对应的 default 方法。
 * <p>
 * <b>为什么用 POJO 而非 record</b>：{@code enabled} 字段需要默认值 {@code true}——
 * YAML 不配置时 Spring Boot 会将 {@code boolean} 初始化为 {@code false}，
 * 与"默认启用"语义矛盾。POJO 可在字段声明处直接赋默认值。
 */
public abstract sealed class AbstractModelCandidate implements ModelCandidate
    permits ChatCandidate, EmbeddingCandidate, RerankCandidate {

    private String id;
    private String provider;
    private String model;
    private int priority;
    private LlmCapability capability;
    private Map<String, Object> params = Map.of();
    private boolean enabled = true;

    @Override public String id() { return id; }
    @Override public String provider() { return provider; }
    @Override public String model() { return model; }
    @Override public int priority() { return priority; }
    @Override public LlmCapability capability() { return capability; }
    @Override public boolean enabled() { return enabled; }
    @Override public Map<String, Object> params() { return params != null ? params : Map.of(); }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public LlmCapability getCapability() { return capability; }
    public void setCapability(LlmCapability capability) { this.capability = capability; }
    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params != null ? params : Map.of(); }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /**
     * 校验必填字段是否已设置
     *
     * @throws ClientException if id, provider, or model is null or blank
     */
    public void validate() {
        if (id == null || id.isBlank()) throw new ClientException(ClientErrorCode.BAD_REQUEST, "候选 ID 不能为空");
        if (provider == null || provider.isBlank()) throw new ClientException(ClientErrorCode.BAD_REQUEST, "供应商不能为空");
        if (model == null || model.isBlank()) throw new ClientException(ClientErrorCode.BAD_REQUEST, "模型名称不能为空");
    }
}
