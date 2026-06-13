package com.smart.rag.infrastructure.llm.config;

import com.smart.rag.infrastructure.llm.AbstractModelCandidate;
import com.smart.rag.infrastructure.llm.ChatCandidate;
import com.smart.rag.infrastructure.llm.EmbeddingCandidate;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.ModelCandidate;
import com.smart.rag.infrastructure.llm.RerankCandidate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 能力模型组——一个能力类型下的所有候选配置
 * <p>
 * 对应 YAML {@code app.llm.capabilities.<chat|embedding|reranking>}。
 * <p>
 * 使用 {@link CandidateProperties} 做 YAML 绑定，
 * 通过 {@link #toModelCandidates(LlmCapability)} 转换为 sealed {@link ModelCandidate} 层次。
 */
public class ModelGroup {

    private String defaultModel;
    private String deepThinkingModel;
    private List<CandidateProperties> candidates = List.of();

    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }

    public String getDeepThinkingModel() { return deepThinkingModel; }
    public void setDeepThinkingModel(String deepThinkingModel) { this.deepThinkingModel = deepThinkingModel; }

    public List<CandidateProperties> getCandidates() { return candidates; }
    public void setCandidates(List<CandidateProperties> candidates) { this.candidates = candidates != null ? candidates : List.of(); }

    /** 按优先级排序的候选列表（enabled=true 且 priority 升序） */
    public List<CandidateProperties> orderedCandidates() {
        return candidates.stream()
            .filter(CandidateProperties::isEnabled)
            .sorted((a, b) -> Integer.compare(a.getPriority(), b.getPriority()))
            .toList();
    }

    /** 按 id 查找候选 */
    public Optional<CandidateProperties> findCandidate(String candidateId) {
        return candidates.stream()
            .filter(c -> candidateId.equals(c.getId()))
            .findFirst();
    }

    /**
     * 将 YAML 绑定属性转换为 sealed ModelCandidate 层次
     *
     * @param capability 该组的能力类型
     * @return 转换后的候选列表
     */
    public List<ModelCandidate> toModelCandidates(LlmCapability capability) {
        return candidates.stream().map(raw -> {
            AbstractModelCandidate c = switch (capability) {
                case CHAT -> {
                    ChatCandidate chat = new ChatCandidate();
                    chat.setSupportsThinking(raw.isSupportsThinking());
                    chat.setSupportsStreaming(raw.isSupportsStreaming());
                    yield chat;
                }
                case EMBEDDING -> {
                    EmbeddingCandidate emb = new EmbeddingCandidate();
                    emb.setDimension(raw.getDimension());
                    yield emb;
                }
                case RERANKING -> new RerankCandidate();
            };
            c.setId(raw.getId());
            c.setProvider(raw.getProvider());
            c.setModel(raw.getModel());
            c.setPriority(raw.getPriority());
            c.setCapability(capability);
            c.setEnabled(raw.isEnabled());
            if (raw.getParams() != null) {
                c.setParams(raw.getParams());
            }
            c.validate();
            return (ModelCandidate) c;
        }).toList();
    }
}
