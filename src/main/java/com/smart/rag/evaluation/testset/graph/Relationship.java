package com.smart.rag.evaluation.testset.graph;

import java.util.Map;

/**
 * 知识图谱有向关系边（对应 ragas {@code Relationship}）。
 *
 * @param source       源节点 id
 * @param target       目标节点 id
 * @param type         边类型
 * @param weight       边强度（实体重叠为匹配分，相似边为余弦值）
 * @param bidirectional 是否双向（路径枚举时双向边可反向通行）
 * @param properties   附加属性（如重叠到的实体名列表）
 */
public record Relationship(
        String source,
        String target,
        RelationshipType type,
        double weight,
        boolean bidirectional,
        Map<String, Object> properties) {

    public Relationship {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    public static Relationship of(String source, String target, RelationshipType type, double weight) {
        return new Relationship(source, target, type, weight, false, Map.of());
    }
}
