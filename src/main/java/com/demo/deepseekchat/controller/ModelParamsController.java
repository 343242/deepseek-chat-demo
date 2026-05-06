package com.demo.deepseekchat.controller;

import com.demo.deepseekchat.model.dto.ModelParamsDTO;
import com.demo.deepseekchat.service.ModelParamsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 模型参数管理 API
 * <p>
 * GET    /api/models/params             - 获取所有模型参数配置
 * GET    /api/models/{modelId}/params   - 获取指定模型参数
 * PUT    /api/models/{modelId}/params   - 创建或更新模型参数（热调整）
 * DELETE /api/models/{modelId}/params   - 删除指定模型参数（恢复默认）
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
    public List<ModelParamsDTO> listAll() {
        return modelParamsService.listAll();
    }

    @GetMapping("/{modelId}/params")
    public ResponseEntity<ModelParamsDTO> get(@PathVariable String modelId) {
        return modelParamsService.getParamsDTO(modelId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    /**
     * 创建或更新模型参数
     * <p>
     * 请求体示例：
     * { "temperature": 0.9, "maxTokens": 2048, "topP": 0.95 }
     * 只更新非 null 字段，null 字段保持原值。
     */
    @PutMapping("/{modelId}/params")
    public ModelParamsDTO saveOrUpdate(@PathVariable String modelId,
                                       @RequestBody ModelParamsDTO dto) {
        return modelParamsService.saveOrUpdate(modelId, dto);
    }

    @DeleteMapping("/{modelId}/params")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String modelId) {
        boolean deleted = modelParamsService.delete(modelId);
        if (deleted) {
            return ResponseEntity.ok(Map.of("modelId", modelId, "message", "已删除，恢复默认参数"));
        }
        return ResponseEntity.notFound().build();
    }
}
