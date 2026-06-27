package com.smart.rag.modelconfig.controller;

import com.smart.rag.chat.service.UserContextProvider;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.response.GlobalResponse;
import com.smart.rag.modelconfig.dto.LlmConfigVO;
import com.smart.rag.modelconfig.dto.UpsertLlmConfigRequest;
import com.smart.rag.modelconfig.entity.LlmModelConfig;
import com.smart.rag.modelconfig.service.LlmModelConfigService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * BYOK 配置 owner 自服务控制器（design §12 — 唯一写入入口）。
 * <p>
 * <b>owner-only</b>：{@code userId} 从 {@link UserContextProvider}（SecurityContext）取，
 * <b>禁 query param 传 userId 越权</b>（design §12.1）。任何已认证用户可管理自己的 BYOK。
 * <p>
 * P1-8：upsert 仅 CHAT；EMBEDDING/RERANKING 由 service 抛 UNSUPPORTED_OPERATION（GlobalResponse 业务码）。
 */
@RestController
@RequestMapping("/api/user/llm-config")
@PreAuthorize("isAuthenticated()")
public class UserLlmConfigController {

    private final LlmModelConfigService service;
    private final UserContextProvider userContextProvider;

    public UserLlmConfigController(LlmModelConfigService service, UserContextProvider userContextProvider) {
        this.service = service;
        this.userContextProvider = userContextProvider;
    }

    /** 列出当前用户的配置（脱敏 VO）。capabilityType 默认 CHAT。 */
    @GetMapping
    public GlobalResponse<List<LlmConfigVO>> list(
            @RequestParam(defaultValue = "CHAT") String capabilityType) {
        Long userId = userContextProvider.getCurrentUserId();
        LlmCapability cap = LlmCapability.valueOf(capabilityType.toUpperCase());
        return GlobalResponse.ok(service.selectAll(userId, cap).stream()
                .map(e -> LlmConfigVO.from(e, service.maskKey(e)))
                .toList());
    }

    /** 查看当前用户的单条配置（service 校验归属，越权 → FORBIDDEN）。 */
    @GetMapping("/{id}")
    public GlobalResponse<LlmConfigVO> get(@PathVariable Long id) {
        Long userId = userContextProvider.getCurrentUserId();
        LlmModelConfig e = service.getOwned(userId, id);
        return GlobalResponse.ok(LlmConfigVO.from(e, service.maskKey(e)));
    }

    /** upsert 当前用户的配置（owner 唯一写入入口）。 */
    @PostMapping
    public GlobalResponse<LlmConfigVO> upsert(@RequestBody UpsertLlmConfigRequest request) {
        Long userId = userContextProvider.getCurrentUserId();
        LlmModelConfig saved = service.upsert(userId, request);
        return GlobalResponse.ok(LlmConfigVO.from(saved, service.maskKey(saved)));
    }

    /** 删除当前用户的配置（owner-only，service 校验 entity.userId == 当前用户）。 */
    @DeleteMapping("/{id}")
    public GlobalResponse<Void> delete(@PathVariable Long id) {
        Long userId = userContextProvider.getCurrentUserId();
        service.delete(userId, id);
        return GlobalResponse.ok();
    }
}
