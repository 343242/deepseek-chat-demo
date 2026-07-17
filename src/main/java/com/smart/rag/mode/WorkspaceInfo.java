package com.smart.rag.mode;

import com.smart.rag.rag.retrieval.RetrievedDocument;

import java.util.List;

/**
 * Agent 检索工作区的只读视图（ISP 窄接口）。
 * <p>
 * 供 {@link ModeChainResult} 持有 agent 工作区元数据，避免 mode 包直接依赖
 * agent.workspace.ToolWorkspace（有状态重型类）。ToolWorkspace 实现本接口，
 * ModeChainResult 只承诺本接口能力，不泄漏 agent 私有状态。
 */
public interface WorkspaceInfo {

    /** 当前检索轮次（Agent ReAct 循环第几轮） */
    int getRetrievalRound();

    /** 累计检索到的文档（已去重、编号） */
    List<RetrievedDocument> getRetrievedDocs();
}
