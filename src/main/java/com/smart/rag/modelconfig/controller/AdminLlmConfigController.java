package com.smart.rag.modelconfig.controller;

import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.response.GlobalResponse;
import com.smart.rag.modelconfig.dto.LlmConfigVO;
import com.smart.rag.modelconfig.service.LlmModelConfigService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * BYOK 配置 admin 只读控制器（design §12.1 — 仅 GET，运维排障/合规审计）。
 * <p>
 * <b>admin 不写</b>：无 POST/PUT/DELETE（模型配置仅 owner 本人可改）。
 * 复用 {@code user:manage} 权限（admin 运维用户时查其 BYOK 配置）。返回脱敏 VO（无明文 key）。
 */
@RestController
@RequestMapping("/api/admin/llm-config")
@PreAuthorize("hasAuthority('user:manage')")
public class AdminLlmConfigController {

    private final LlmModelConfigService service;

    public AdminLlmConfigController(LlmModelConfigService service) {
        this.service = service;
    }

    /** 查任意用户的配置（脱敏 VO，审计用）。 */
    @GetMapping
    public GlobalResponse<List<LlmConfigVO>> list(
            @RequestParam Long userId,
            @RequestParam(defaultValue = "CHAT") String capabilityType) {
        LlmCapability cap = LlmCapability.valueOf(capabilityType.toUpperCase());
        return GlobalResponse.ok(service.selectAll(userId, cap).stream()
                .map(e -> LlmConfigVO.from(e, service.maskKey(e)))
                .toList());
    }
}
