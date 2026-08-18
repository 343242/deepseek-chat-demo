package com.smart.rag.evaluation.testset.graph;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识图谱节点（对应 ragas {@code testset/graph.py::Node}，预切块模式下恒为 CHUNK 类型）。
 * <p>
 * 核心字段（id/pageContent/metadata/embedding）构造后不变；实体与主题是分阶段附加的富化信息，
 * 由编排器在单线程阶段边界写入（loader/extractor 返回结果、编排器统一 attach），不在并发任务内修改。
 * </p>
 */
public final class Node {

    private final String id;
    private final String pageContent;
    private final Map<String, Object> metadata;
    private final double[] embedding;

    private Set<String> entities = Set.of();
    private List<String> themes = List.of();

    public Node(String id, String pageContent, Map<String, Object> metadata, double[] embedding) {
        this.id = id;
        this.pageContent = pageContent;
        this.metadata = Map.copyOf(metadata);
        this.embedding = embedding;
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

    /** chunk 在 vector_store 中的现成向量；主题相似边的唯一输入。 */
    public double[] embedding() {
        return embedding;
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
