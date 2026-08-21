package com.smart.rag.evaluation.testset.graph;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识图谱节点（对应 ragas {@code testset/graph.py::Node}，预切块模式下恒为 CHUNK 类型）。
 * <p>
 * 核心字段（id/pageContent/metadata）构造后不变；摘要/摘要向量/实体/主题是分阶段附加的
 * 富化信息，由编排器在单线程阶段边界写入（extractor 返回结果、编排器统一 attach），
 * 不在并发任务内修改。
 * </p>
 */
public final class Node {

    private final String id;
    private final String pageContent;
    private final Map<String, Object> metadata;

    private String summary = "";
    private double[] summaryEmbedding;
    private Set<String> entities = Set.of();
    private List<String> themes = List.of();

    public Node(String id, String pageContent, Map<String, Object> metadata) {
        this.id = id;
        this.pageContent = pageContent;
        this.metadata = Map.copyOf(metadata);
    }

    public String id() {
        return id;
    }

    public String pageContent() {
        return pageContent;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    /** chunk 摘要（SummaryExtractor 产出；空 = 未生成或失败，被过滤器跳过） */
    public String summary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary == null ? "" : summary;
    }

    /** 摘要向量（summary embedding；null = 无摘要或嵌入失败，不参与相似边与 persona 分组） */
    public double[] summaryEmbedding() {
        return summaryEmbedding;
    }

    public void setSummaryEmbedding(double[] summaryEmbedding) {
        this.summaryEmbedding = summaryEmbedding;
    }

    /** 实体规范名集合（rag_entity.name_norm，经实体中心索引层 ETL 产出）。 */
    public Set<String> entities() {
        return entities;
    }

    public void setEntities(Set<String> entities) {
        this.entities = Set.copyOf(entities);
    }

    public List<String> themes() {
        return themes;
    }

    public void setThemes(List<String> themes) {
        this.themes = List.copyOf(themes);
    }

    /** 供合成器拼接提示词使用的主题摘要（无主题时回退到实体名）。 */
    public List<String> themesOrEntities() {
        if (!themes.isEmpty()) {
            return themes;
        }
        return entities.stream().limit(5).toList();
    }
}
