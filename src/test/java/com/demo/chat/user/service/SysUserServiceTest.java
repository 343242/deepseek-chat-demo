package com.demo.chat.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.demo.chat.exception.BusinessException;
import com.demo.chat.security.service.TokenCacheService;
import com.demo.chat.user.dto.AssignRolesRequest;
import com.demo.chat.user.entity.SysRole;
import com.demo.chat.user.entity.SysUser;
import com.demo.chat.user.entity.SysUserRole;
import com.demo.chat.user.mapper.SysRoleMapper;
import com.demo.chat.user.mapper.SysUserMapper;
import com.demo.chat.user.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SysUserService 单元测试")
class SysUserServiceTest {

    @Mock private SysUserMapper userMapper;
    @Mock private SysUserRoleMapper userRoleMapper;
    @Mock private SysRoleMapper roleMapper;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private TokenCacheService tokenCacheService;
    @Mock private AuthService authService;

    @InjectMocks
    private SysUserService sysUserService;

    private SysUser buildUser() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("testuser");
        user.setNickname("Test");
        user.setEmail("test@example.com");
        user.setStatus(1);
        user.setDeleted(0);
        return user;
    }

    // ==================== Update Status ====================

    @Nested
    @DisplayName("修改用户状态")
    class UpdateStatusTests {

        @Test
        @DisplayName("updateUserStatus_disabled: status=0 调用 revokeAllUserTokens")
        void updateUserStatus_disabled() {
            SysUser user = buildUser();
            when(userMapper.selectById(1L)).thenReturn(user);
            when(userMapper.updateById(any(SysUser.class))).thenReturn(1);

            Map<String, Object> result = sysUserService.updateUserStatus(1L, 0);

            assertEquals(1L, result.get("userId"));
            verify(authService).revokeAllUserTokens(1L);
            verify(tokenCacheService, never()).clearUserStatus(anyLong());
        }

        @Test
        @DisplayName("updateUserStatus_enabled: status=1 调用 clearUserStatus")
        void updateUserStatus_enabled() {
            SysUser user = buildUser();
            when(userMapper.selectById(1L)).thenReturn(user);
            when(userMapper.updateById(any(SysUser.class))).thenReturn(1);

            Map<String, Object> result = sysUserService.updateUserStatus(1L, 1);

            assertEquals(1L, result.get("userId"));
            verify(tokenCacheService).clearUserStatus(1L);
            verify(authService, never()).revokeAllUserTokens(anyLong());
        }

        @Test
        @DisplayName("updateUserStatus_userNotFound: 抛 BusinessException")
        void updateUserStatus_userNotFound() {
            when(userMapper.selectById(999L)).thenReturn(null);

            assertThrows(BusinessException.class,
                    () -> sysUserService.updateUserStatus(999L, 1));
        }
    }

    // ==================== Assign Roles ====================

    @Nested
    @DisplayName("分配角色")
    class AssignRolesTests {

        @Test
        @DisplayName("assignRoles_success: 正常分配角色")
        void assignRoles_success() {
            SysUser user = buildUser();
            when(userMapper.selectById(1L)).thenReturn(user);

            SysRole role = new SysRole();
            role.setId(10L);
            role.setRoleName("ADMIN");
            when(roleMapper.selectBatchIds(List.of(10L))).thenReturn(List.of(role));

            // Execute transaction callback immediately
            doAnswer(invocation -> {
                java.util.function.Consumer<org.springframework.transaction.TransactionStatus> consumer = invocation.getArgument(0);
                consumer.accept(null);
                return null;
            }).when(transactionTemplate).executeWithoutResult(any());

            when(userRoleMapper.insert(any(SysUserRole.class))).thenReturn(1);

            Map<String, Object> result = sysUserService.assignRoles(1L, new AssignRolesRequest(List.of(10L)));

            assertEquals(1L, result.get("userId"));
            verify(tokenCacheService).evictUserPermissions(1L);
        }

        @Test
        @DisplayName("assignRoles_duplicateRoleIds: 重复 roleId 被去重")
        void assignRoles_duplicateRoleIds() {
            SysUser user = buildUser();
            when(userMapper.selectById(1L)).thenReturn(user);

            SysRole role = new SysRole();
            role.setId(10L);
            // Deduped list has 1 element but input has 2
            when(roleMapper.selectBatchIds(List.of(10L))).thenReturn(List.of(role));

            doAnswer(invocation -> {
                java.util.function.Consumer<org.springframework.transaction.TransactionStatus> consumer = invocation.getArgument(0);
                consumer.accept(null);
                return null;
            }).when(transactionTemplate).executeWithoutResult(any());

            when(userRoleMapper.insert(any(SysUserRole.class))).thenReturn(1);

            Map<String, Object> result = sysUserService.assignRoles(1L, new AssignRolesRequest(List.of(10L, 10L)));

            @SuppressWarnings("unchecked")
            List<Long> assignedRoles = (List<Long>) result.get("roles");
            assertEquals(1, assignedRoles.size());
            assertEquals(10L, assignedRoles.get(0));
        }

        @Test
        @DisplayName("assignRoles_userNotFound: 抛 BusinessException")
        void assignRoles_userNotFound() {
            when(userMapper.selectById(999L)).thenReturn(null);

            assertThrows(BusinessException.class,
                    () -> sysUserService.assignRoles(999L, new AssignRolesRequest(List.of(10L))));
        }

        @Test
        @DisplayName("assignRoles_nonExistentRole: roleId 不存在时抛 BusinessException")
        void assignRoles_nonExistentRole() {
            SysUser user = buildUser();
            when(userMapper.selectById(1L)).thenReturn(user);
            when(roleMapper.selectBatchIds(List.of(999L))).thenReturn(List.of()); // role not found

            assertThrows(BusinessException.class,
                    () -> sysUserService.assignRoles(1L, new AssignRolesRequest(List.of(999L))));
        }
    }

    // ==================== Delete User ====================

    @Nested
    @DisplayName("删除用户")
    class DeleteUserTests {

        @Test
        @DisplayName("deleteUser_success: 正常删除 + 吊销 token")
        void deleteUser_success() {
            SysUser user = buildUser();
            when(userMapper.selectById(1L)).thenReturn(user);
            when(userMapper.deleteById(1L)).thenReturn(1);

            Map<String, Object> result = sysUserService.deleteUser(1L);

            assertEquals(1L, result.get("userId"));
            verify(authService).revokeAllUserTokens(1L);
        }
    }

    // ==================== List Users ====================

    @Nested
    @DisplayName("分页查询用户")
    class ListUsersTests {

        @Test
        @DisplayName("listUsers_success: 分页查询")
        @SuppressWarnings("unchecked")
        void listUsers_success() {
            Page<SysUser> page = new Page<>(1, 10);
            page.setRecords(List.of(buildUser()));
            page.setTotal(1);
            page.setPages(1);
            when(userMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(page);

            Map<String, Object> result = sysUserService.listUsers(1, 10, null);

            assertEquals(1L, result.get("total"));
            assertNotNull(result.get("content"));
        }
    }
}
