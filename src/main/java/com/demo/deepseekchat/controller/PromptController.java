package com.demo.deepseekchat.controller;

import com.demo.deepseekchat.model.dto.SystemPromptDTO;
import com.demo.deepseekchat.service.SystemPromptService;
import org.springframework.http.ResponseEntity;
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
                                        @RequestBody Map<String, String> body) {
        String promptText = body.get("promptText");
        if (promptText == null || promptText.isBlank()) {
            throw new IllegalArgumentException("promptText 不能为空");
        }
        return promptService.saveOrUpdate(modelId, promptText);
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
