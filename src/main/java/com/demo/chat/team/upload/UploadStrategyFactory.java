package com.demo.chat.team.upload;

import com.demo.chat.common.errorcode.ErrorCode;
import com.demo.chat.exception.BusinessException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * 上传策略工厂 — 根据 teamId 路由到对应策略
 * <p>
 * 路由规则：
 * <ul>
 *   <li>teamId = null → {@link PersonalUploadStrategy}（个人上传）</li>
 *   <li>teamId ≠ null → {@link TeamUploadStrategy}（团队上传，Phase 3 实现）</li>
 * </ul>
 * <p>
 * Phase 2 阶段 teamId ≠ null 时抛出占位异常，Phase 3 注入 TeamUploadStrategy 后启用。
 */
@Component
public class UploadStrategyFactory {

    private final PersonalUploadStrategy personalUploadStrategy;

    public UploadStrategyFactory(PersonalUploadStrategy personalUploadStrategy) {
        this.personalUploadStrategy = personalUploadStrategy;
    }

    /**
     * 根据 teamId 路由到对应策略
     *
     * @param teamId 团队 ID（null = 个人上传）
     * @return 上传策略
     * @throws BusinessException NOT_TEAM_MEMBER（Phase 2 阶段团队功能尚未实现）
     */
    public UploadStrategy route(@Nullable Long teamId) {
        if (teamId != null) {
            throw new BusinessException(ErrorCode.NOT_TEAM_MEMBER, "团队功能尚未实现");
        }
        return personalUploadStrategy;
    }
}
