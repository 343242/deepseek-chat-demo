package com.smart.rag.chat.service;

import com.smart.rag.chat.dto.ModelParamsDTO;
import com.smart.rag.chat.entity.ModelParams;

import java.util.List;
import java.util.Optional;

/**
 * 模型参数管理服务接口
 */
public interface ModelParamsService {

    ModelParams getParams(String modelId);

    Optional<ModelParamsDTO> getParamsDTO(String modelId);

    List<ModelParamsDTO> listAll();

    ModelParamsDTO saveOrUpdate(String modelId, ModelParamsDTO dto);

    boolean delete(String modelId);
}
