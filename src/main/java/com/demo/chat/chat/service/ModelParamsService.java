package com.demo.chat.chat.service;

import com.demo.chat.chat.dto.ModelParamsDTO;
import com.demo.chat.chat.entity.ModelParams;

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
