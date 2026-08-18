package com.smart.rag.evaluation.testset.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 知识图谱（对应 ragas {@code KnowledgeGraph}）：CHUNK 节点 + 关系边。
 * <p>
 * 构建期可变（transforms 阶段追加关系），合成期只读；线程模型与 {@link Node} 一致——
 * 阶段边界由编排器单线程写入。
 * </p>
 */
public final class KnowledgeGraph {

    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final List<Relationship> relationships = new ArrayList<>();

    public void addNode(Node node) {
        nodes.put(node.id(), node);
    }

    public void addRelationship(Relationship relationship) {
        relationships.add(relationship);
    }

    public void addRelationships(List<Relationship> toAdd) {
        relationships.addAll(toAdd);
    }

    public Optional<Node> node(String id) {
        return Optional.ofNullable(nodes.get(id));
    }

    public List<Node> nodes() {
        return List.copyOf(nodes.values());
    }

    public int nodeCount() {
        return nodes.size();
    }

    public List<Relationship> relationships() {
        return List.copyOf(relationships);
    }

    public List<Relationship> relationships(Predicate<Relationship> condition) {
        return relationships.stream().filter(condition).toList();
    }

    public long relationshipCount(RelationshipType type) {
        return relationships.stream().filter(rel -> rel.type() == type).count();
    }

    /** 有实体的节点（SingleHopSpecific 合成器的候选集，对应 ragas specific.py 的节点过滤）。 */
    public List<Node> nodesWithEntities() {
        return nodes.values().stream().filter(n -> !n.entities().isEmpty()).toList();
    }

    /** 按节点 id 集合取节点（保持图谱内部顺序）。 */
    public Set<Node> nodesByIds(Set<String> ids) {
        var result = new LinkedHashSet<Node>(ids.size());
        ids.forEach(id -> nodes.values().stream()
                .filter(n -> n.id().equals(id))
                .findFirst()
                .ifPresent(result::add));
        return result;
    }
}
