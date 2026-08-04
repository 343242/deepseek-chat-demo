package com.smart.rag.chat.service;

import com.smart.rag.chat.dto.ModelVO;
import java.util.List;

/**
 * 模型管理服务接口
 */
public interface ModelService {

    List<String> listModelIds();

    /**
     * 仅列出 CHAT 能力模型的候选 ID（供聊天端点 /api/models 使用）。
     * <p>
     * 普通用户在使用时只能选 CHAT 模型；Embedding/Rerank 不对聊天场景暴露。
     */
    List<String> listChatModelIds();

    boolean isModelAvailable(String candidateId);

    /**
     * 列出全部已注册模型的详情（含 provider / 能力标签 / 可用状态）。
     *
     * @param capability 可选能力过滤（CHAT / EMBEDDING / RERANKING），null = 全部
     */
    List<ModelVO> listModelDetails(String capability);

    boolean refreshModels();
}
