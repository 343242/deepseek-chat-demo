package com.smart.rag.evaluation.testset.synthesizers;

import com.smart.rag.evaluation.testset.graph.Node;

import java.util.List;

/**
 * 出题场景（对应 ragas BaseScenario 体系）。sealed：单跳（term + 单节点）
 * 与多跳（主题组合 + 节点集）两种形态。
 */
public sealed interface Scenario permits SingleHopScenario, MultiHopScenario {

    Persona persona();

    QueryStyle style();

    QueryLength length();
}

/**
 * 单跳场景：围绕一个主题词对一个 chunk 出题。
 */
record SingleHopScenario(String term, Node node, Persona persona, QueryStyle style,
                         QueryLength length) implements Scenario {
}

/**
 * 多跳场景：主题组合跨多个 chunk 出题。
 */
record MultiHopScenario(List<String> combinations, List<Node> nodes, Persona persona,
                        QueryStyle style, QueryLength length) implements Scenario {
}
