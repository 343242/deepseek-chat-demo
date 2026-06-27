package com.smart.rag.modelconfig.event;

import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import com.smart.rag.modelconfig.mapper.LlmModelConfigMapper;
import com.smart.rag.user.event.UserDeletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * BYOK 用户生命周期联动（design §14.1 R2 — 孤儿资源清理）。
 * <p>
 * 用户被删除时：清 BYOK 快照缓存 + 熔断器（{@code invalidateUser}）+ 逻辑删除 {@code llm_config}
 * （审计行保留，查询不命中）。用 Spring 事件解耦，避免 llm 模块反向依赖 user 模块。
 * <p>
 * <b>disable 路径</b>：SysUserServiceImpl 当前无 disable 方法（仅 deleteUser），故无 UserDisabledEvent；
 * 认证层 token 失效兜底，缓存 TTL 自然过期；如后续加 disable，发 UserDisabledEvent → 仅 invalidateUser（llm_config 保留）。
 *
 * @see LlmClientRegistry#invalidateUser(Long)
 * @see LlmModelConfigMapper#markDeletedByUser(Long)
 */
@Component
public class LlmUserLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(LlmUserLifecycleListener.class);

    private final LlmClientRegistry registry;
    private final LlmModelConfigMapper mapper;

    public LlmUserLifecycleListener(LlmClientRegistry registry, LlmModelConfigMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    @EventListener
    public void onUserDeleted(UserDeletedEvent event) {
        Long userId = event.userId();
        registry.invalidateUser(userId);              // 清缓存 + 熔断器 evict（P1-6）
        int affected = mapper.markDeletedByUser(userId); // llm_config 逻辑删除（审计保留，R2）
        log.info("User {} deleted: BYOK cache invalidated, {} llm_config rows soft-deleted", userId, affected);
    }
}
