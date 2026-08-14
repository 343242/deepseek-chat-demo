package com.smart.rag.rag.upload;

import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 上传策略路由器 — 替代原 {@code team.upload.UploadStrategyFactory}。
 * <p>
 * 通过 Spring 自动收集容器内全部 {@link UploadStrategy} 实现（含 team 模块的
 * {@code TeamUploadStrategy}），按 {@link UploadStrategy#supports(Long)} 选择，
 * 使 rag 无需感知具体实现所在模块，彻底切断 rag → team 的反向依赖。
 */
@Component
public class UploadStrategyRouter {

    private final List<UploadStrategy> strategies;

    public UploadStrategyRouter(List<UploadStrategy> strategies) {
        this.strategies = strategies;
    }

    /**
     * 根据 teamId 路由到匹配的上传策略。
     *
     * @param teamId 团队 ID（null = 个人上传）
     * @return 唯一匹配的上传策略
     * @throws ServiceException 无匹配策略（容器配置错误，服务端内部错误）
     */
    public UploadStrategy route(@Nullable Long teamId) {
        return strategies.stream()
                .filter(s -> s.supports(teamId))
                .findFirst()
                .orElseThrow(() -> new ServiceException(ServiceErrorCode.INTERNAL_ERROR,
                        "No UploadStrategy for teamId=" + teamId));
    }
}
