package com.smart.rag.infrastructure.llm.config;

import java.util.Map;

/**
 * 候选模型 YAML 绑定 POJO
 * <p>
 * Spring Boot 无法直接绑定 sealed interface {@link com.smart.rag.infrastructure.llm.ModelCandidate}，
 * 因此用此 POJO 做中间绑定，再由 Registry 转换为具体的 sealed 子类型。
 */
public class CandidateProperties {

    private String id;
    private String provider;
    private String model;
    private int priority;
    private boolean supportsThinking;
    private boolean supportsStreaming;
    private int dimension;
    private boolean enabled = true;
    private Map<String, Object> params = Map.of();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public boolean isSupportsThinking() { return supportsThinking; }
    public void setSupportsThinking(boolean supportsThinking) { this.supportsThinking = supportsThinking; }

    public boolean isSupportsStreaming() { return supportsStreaming; }
    public void setSupportsStreaming(boolean supportsStreaming) { this.supportsStreaming = supportsStreaming; }

    public int getDimension() { return dimension; }
    public void setDimension(int dimension) { this.dimension = dimension; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }

    @Override
    public String toString() {
        return "CandidateProperties{id='" + id + "', provider='" + provider
            + "', model='" + model + "', priority=" + priority
            + ", enabled=" + enabled + '}';
    }
}
