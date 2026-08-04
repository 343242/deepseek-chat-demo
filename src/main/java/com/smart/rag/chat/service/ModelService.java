package com.smart.rag.chat.service;

import com.smart.rag.chat.dto.ModelVO;
import java.util.List;

/**
 * 模型管理服务接口
 */
public interface ModelService {

    List<String> listModelIds();

    boolean isModelAvailable(String candidateId);

    /**
     * 列出全部已注册模型的详情（含 provider / 能力标签 / 可用状态）。
     *
     * @param capability 可选能力过滤（CHAT / EMBEDDING / RERANKING），null = 全部
     */
    List<ModelVO> listModelDetails(String capability);

    boolean refreshModels();
}
