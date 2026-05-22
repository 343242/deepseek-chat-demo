package com.smart.rag.chat.service;

import com.smart.rag.chat.dto.SystemPromptDTO;

import java.util.List;
import java.util.Optional;

/**
 * System Prompt 管理服务接口
 */
public interface SystemPromptService {

    String getPrompt(String modelId);

    Optional<SystemPromptDTO> getPromptDTO(String modelId);

    List<SystemPromptDTO> listAll();

    SystemPromptDTO saveOrUpdate(String modelId, String promptText);

    boolean delete(String modelId);
}
