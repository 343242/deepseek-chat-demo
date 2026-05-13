package com.demo.chat.team.upload;

import com.demo.chat.common.errorcode.ErrorCode;
import com.demo.chat.common.upload.UploadStrategy;
import com.demo.chat.exception.BusinessException;
import com.demo.chat.rag.upload.PersonalUploadStrategy;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * 上传策略工厂 — 根据 teamId 路由到对应策略
 * <p>
 * 路由规则：
 * <ul>
 *   <li>teamId = null → {@link PersonalUploadStrategy}（个人上传）</li>
 *   <li>teamId ≠ null → {@link TeamUploadStrategy}（团队上传）</li>
 * </ul>
 */
@Component
public class UploadStrategyFactory {

    private final PersonalUploadStrategy personalUploadStrategy;
    private final TeamUploadStrategy teamUploadStrategy;

    public UploadStrategyFactory(PersonalUploadStrategy personalUploadStrategy,
                                  TeamUploadStrategy teamUploadStrategy) {
        this.personalUploadStrategy = personalUploadStrategy;
        this.teamUploadStrategy = teamUploadStrategy;
    }

    /**
     * 根据 teamId 路由到对应策略
     *
     * @param teamId 团队 ID（null = 个人上传）
     * @return 上传策略
     */
    public UploadStrategy route(@Nullable Long teamId) {
        if (teamId == null) {
            return personalUploadStrategy;
        }
        return teamUploadStrategy;
    }
}
