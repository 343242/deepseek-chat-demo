package com.demo.chat.chat.context;

import com.demo.chat.user.entity.SysPermission;
import com.demo.chat.user.entity.SysRole;
import com.demo.chat.user.entity.SysUser;
import com.demo.chat.user.mapper.SysRoleMapper;
import com.demo.chat.user.mapper.SysRolePermissionMapper;
import com.demo.chat.user.mapper.SysUserMapper;
import com.demo.chat.user.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DefaultUserProfileResolver 测试
 */
@ExtendWith(MockitoExtension.class)
class DefaultUserProfileResolverTest {

    @Mock
    private SysUserMapper userMapper;

    @Mock
    private SysUserRoleMapper userRoleMapper;

    @Mock
    private SysRoleMapper roleMapper;

    @Mock
    private SysRolePermissionMapper rolePermissionMapper;

    @InjectMocks
    private DefaultUserProfileResolver resolver;

    @Nested
    @DisplayName("resolve")
    class Resolve {

        @Test
        @DisplayName("正常用户 → 返回完整画像")
        void normalUser_fullProfile() {
            SysUser user = new SysUser();
            user.setId(1L);
            user.setNickname("张三");

            when(userMapper.selectById(1L)).thenReturn(user);
            when(userRoleMapper.selectRoleIdsByUserId(1L)).thenReturn(List.of(10L, 20L));

            SysRole role1 = new SysRole();
            role1.setId(10L);
            role1.setRoleName("ADMIN");
            SysRole role2 = new SysRole();
            role2.setId(20L);
            role2.setRoleName("USER");
            when(roleMapper.selectByIds(List.of(10L, 20L))).thenReturn(List.of(role1, role2));

            SysPermission perm = new SysPermission();
            perm.setId(1L);
            perm.setPermissionName("chat:send");
            when(rolePermissionMapper.selectPermissionsByRoleIds(List.of(10L, 20L)))
                    .thenReturn(List.of(perm));

            UserContext ctx = resolver.resolve(1L);

            assertEquals("张三", ctx.nickname());
            assertEquals(Set.of("ADMIN", "USER"), ctx.roles());
            assertEquals(Set.of("chat:send"), ctx.permissions());
        }

        @Test
        @DisplayName("用户不存在 → 返回默认值")
        void userNotFound_defaultValues() {
            when(userMapper.selectById(999L)).thenReturn(null);

            UserContext ctx = resolver.resolve(999L);

            assertEquals(999L, ctx.userId());
            assertEquals("unknown", ctx.nickname());
            assertTrue(ctx.roles().isEmpty());
            assertTrue(ctx.permissions().isEmpty());
        }

        @Test
        @DisplayName("无角色 → 空角色和权限")
        void noRoles_emptySets() {
            SysUser user = new SysUser();
            user.setId(2L);
            user.setNickname("李四");

            when(userMapper.selectById(2L)).thenReturn(user);
            when(userRoleMapper.selectRoleIdsByUserId(2L)).thenReturn(List.of());

            UserContext ctx = resolver.resolve(2L);

            assertEquals("李四", ctx.nickname());
            assertTrue(ctx.roles().isEmpty());
            assertTrue(ctx.permissions().isEmpty());
            // 不应调用 roleMapper 和 rolePermissionMapper
            verifyNoInteractions(roleMapper, rolePermissionMapper);
        }
    }
}
