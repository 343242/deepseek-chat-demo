package com.smart.rag.chat.context;
import com.smart.rag.mode.UserContext;

/**
 * 用户画像解析策略
 * <p>
 * 从系统数据源解析用户的基本信息和角色/权限。
 * 默认实现从 sys_user / sys_role / sys_permission 表查询。
 * 可替换为 LDAP、OAuth2 userinfo 等外部数据源。
 */
public interface UserProfileResolver {

    /**
     * 解析指定用户的画像信息
     *
     * @param userId 用户 ID
     * @return 用户画像，用户不存在时返回包含默认值的 UserContext
     */
    UserContext resolve(Long userId);
}
