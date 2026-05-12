package com.demo.chat.chat.context;

import com.demo.chat.user.entity.SysPermission;
import com.demo.chat.user.entity.SysRole;
import com.demo.chat.user.entity.SysUser;
import com.demo.chat.user.mapper.SysRoleMapper;
import com.demo.chat.user.mapper.SysRolePermissionMapper;
import com.demo.chat.user.mapper.SysUserMapper;
import com.demo.chat.user.mapper.SysUserRoleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 默认用户画像解析器
 * <p>
 * 从数据库查询用户的角色和权限信息。
 * 依赖 {@link SysRoleMapper#selectByIds} 批量方法避免 N+1 查询。
 */
@Component
public class DefaultUserProfileResolver implements UserProfileResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultUserProfileResolver.class);

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;

    public DefaultUserProfileResolver(SysUserMapper userMapper,
                                      SysUserRoleMapper userRoleMapper,
                                      SysRoleMapper roleMapper,
                                      SysRolePermissionMapper rolePermissionMapper) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
    }

    @Override
    public UserContext resolve(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            log.debug("User not found: userId={}", userId);
            return new UserContext(userId, "unknown", Set.of(), Set.of());
        }

        // 1. 查询用户关联的角色 ID（已有批量方法）
        List<Long> roleIds = userRoleMapper.selectRoleIdsByUserId(userId);
        if (roleIds.isEmpty()) {
            return new UserContext(userId, user.getNickname(), Set.of(), Set.of());
        }

        // 2. 批量查角色名（避免 N+1）
        List<SysRole> roles = roleMapper.selectByIds(roleIds);
        Set<String> roleNames = roles.stream()
                .map(SysRole::getRoleName)
                .collect(Collectors.toSet());

        // 3. 批量查权限（已有批量方法）
        List<SysPermission> permissions = rolePermissionMapper.selectPermissionsByRoleIds(roleIds);
        Set<String> permissionNames = permissions.stream()
                .map(SysPermission::getPermissionName)
                .collect(Collectors.toSet());

        return new UserContext(userId, user.getNickname(), roleNames, permissionNames);
    }
}
