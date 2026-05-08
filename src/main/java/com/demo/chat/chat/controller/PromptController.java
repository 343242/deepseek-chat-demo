package com.demo.chat.chat.controller;

import com.demo.chat.exception.BusinessException;

import com.demo.chat.chat.dto.SystemPromptDTO;
import com.demo.chat.chat.dto.SystemPromptUpdateRequest;
import com.demo.chat.chat.service.SystemPromptService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * System Prompt 管理 API
 * <p>
 * GET    /api/prompts              - 获取所有 system prompt 配置
 * GET    /api/prompts/{modelId}    - 获取指定模型的 system prompt
 * PUT    /api/prompts/{modelId}    - 创建或更新 system prompt
 * DELETE /api/prompts/{modelId}    - 删除指定模型的 system prompt
 */
@RestController
@RequestMapping("/api/prompts")
@PreAuthorize("hasAuthority('prompt:manage')")
public class PromptController {

    private final SystemPromptService promptService;

    public PromptController(SystemPromptService promptService) {
        this.promptService = promptService;
    }

    @GetMapping
    public List<SystemPromptDTO> listAll() {
        return promptService.listAll();
    }

    @GetMapping("/{modelId}")
    public ResponseEntity<SystemPromptDTO> get(@PathVariable String modelId) {
        return promptService.getPromptDTO(modelId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @PutMapping("/{modelId}")
    public SystemPromptDTO saveOrUpdate(@PathVariable String modelId,
                                        @RequestBody SystemPromptUpdateRequest request) {
        if (request.promptText() == null || request.promptText().isBlank()) {
            throw new BusinessException("promptText 不能为空");
        }
        return promptService.saveOrUpdate(modelId, request.promptText());
    }

    @DeleteMapping("/{modelId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String modelId) {
        boolean deleted = promptService.delete(modelId);
        if (deleted) {
            return ResponseEntity.ok(Map.of("modelId", modelId, "message", "已删除"));
        }
        return ResponseEntity.notFound().build();
    }
}
