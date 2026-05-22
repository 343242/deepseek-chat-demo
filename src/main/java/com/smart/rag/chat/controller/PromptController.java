package com.smart.rag.chat.controller;

import com.smart.rag.chat.dto.SystemPromptDTO;
import com.smart.rag.chat.dto.SystemPromptUpdateRequest;
import com.smart.rag.chat.service.SystemPromptService;
import com.smart.rag.common.response.GlobalResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * System Prompt 管理 API
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
    public GlobalResponse<List<SystemPromptDTO>> listAll() {
        return GlobalResponse.ok(promptService.listAll());
    }

    @GetMapping("/{modelId}")
    public GlobalResponse<SystemPromptDTO> get(@PathVariable String modelId) {
        return GlobalResponse.ok(promptService.getPromptDTO(modelId).orElse(null));
    }

    @PutMapping("/{modelId}")
    public GlobalResponse<SystemPromptDTO> saveOrUpdate(@PathVariable String modelId,
                                                         @Valid @RequestBody SystemPromptUpdateRequest request) {
        return GlobalResponse.ok(promptService.saveOrUpdate(modelId, request.promptText()));
    }

    @DeleteMapping("/{modelId}")
    public GlobalResponse<Void> delete(@PathVariable String modelId) {
        boolean deleted = promptService.delete(modelId);
        if (deleted) {
            return GlobalResponse.ok("已删除");
        }
        return GlobalResponse.ok();
    }
}
