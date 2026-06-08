package com.smart.rag.chat.controller;

import com.smart.rag.chat.dto.ModelParamsDTO;
import com.smart.rag.chat.service.ModelParamsService;
import com.smart.rag.infrastructure.response.GlobalResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 模型参数管理 API
 */
@RestController
@RequestMapping("/api/models")
@PreAuthorize("hasAuthority('model:config')")
public class ModelParamsController {

    private final ModelParamsService modelParamsService;

    public ModelParamsController(ModelParamsService modelParamsService) {
        this.modelParamsService = modelParamsService;
    }

    @GetMapping("/params")
    public GlobalResponse<List<ModelParamsDTO>> listAll() {
        return GlobalResponse.ok(modelParamsService.listAll());
    }

    @GetMapping("/{modelId}/params")
    public GlobalResponse<ModelParamsDTO> get(@PathVariable String modelId) {
        return GlobalResponse.ok(modelParamsService.getParamsDTO(modelId).orElse(null));
    }

    @PostMapping("/{modelId}/params")
    public GlobalResponse<ModelParamsDTO> saveOrUpdate(@PathVariable String modelId,
                                                        @Valid @RequestBody ModelParamsDTO dto) {
        return GlobalResponse.ok(modelParamsService.saveOrUpdate(modelId, dto));
    }

    @PostMapping("/{modelId}/params/delete")
    public GlobalResponse<Void> delete(@PathVariable String modelId) {
        boolean deleted = modelParamsService.delete(modelId);
        if (deleted) {
            return GlobalResponse.ok("已删除，恢复默认参数");
        }
        return GlobalResponse.ok();
    }
}
