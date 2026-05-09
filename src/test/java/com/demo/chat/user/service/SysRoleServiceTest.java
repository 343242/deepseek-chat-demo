package com.demo.chat.user.service;

import com.demo.chat.user.service.impl.SysRoleServiceImpl;
import com.demo.chat.exception.BusinessException;
import com.demo.chat.security.service.TokenCacheService;
import com.demo.chat.user.entity.SysPermission;
import com.demo.chat.user.entity.SysRole;
import com.demo.chat.user.entity.SysRolePermission;
import com.demo.chat.user.mapper.SysPermissionMapper;
import com.demo.chat.user.mapper.SysRoleMapper;
import com.demo.chat.user.mapper.SysRolePermissionMapper;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SysRoleService 单元测试")
class SysRoleServiceTest {

    @Mock private SysRoleMapper roleMapper;
    @Mock private SysRolePermissionMapper rolePermissionMapper;
    @Mock private SysPermissionMapper permissionMapper;
    @Mock private SysUserRoleMapper userRoleMapper;
    @Mock private TokenCacheService tokenCacheService;
    @Mock private TransactionTemplate transactionTemplate;

    @InjectMocks
    private SysRoleServiceImpl sysRoleService;

    @Nested
    @DisplayName("创建角色")
    class CreateRoleTests {

        @Test
        @DisplayName("createRole_success: 正常创建")
        void createRole_success() {
            when(roleMapper.selectByRoleName("EDITOR")).thenReturn(Optional.empty());
            when(roleMapper.insert(any(SysRole.class))).thenReturn(1);

            SysRole result = sysRoleService.createRole("EDITOR", "编辑者");

            assertEquals("EDITOR", result.getRoleName());
            verify(roleMapper).insert(any(SysRole.class));
        }

        @Test
        @DisplayName("createRole_duplicateName: 名字重复时抛 BusinessException")
        void createRole_duplicateName() {
            SysRole existing = new SysRole();
            existing.setId(1L);
            existing.setRoleName("ADMIN");
            when(roleMapper.selectByRoleName("ADMIN")).thenReturn(Optional.of(existing));

            assertThrows(BusinessException.class, () -> sysRoleService.createRole("ADMIN", "管理员"));
        }
    }

    @Nested
    @DisplayName("删除角色")
    class DeleteRoleTests {

        @Test
        @DisplayName("deleteRole_success: 级联删除 + 清缓存")
        void deleteRole_success() {
            SysRole role = new SysRole();
            role.setId(1L);
            when(roleMapper.selectById(1L)).thenReturn(role);
            when(userRoleMapper.selectUserIdsByRoleId(1L)).thenReturn(List.of(10L));

            sysRoleService.deleteRole(1L);

            verify(rolePermissionMapper).deleteByRoleId(1L);
            verify(userRoleMapper).deleteByRoleId(1L);
            verify(roleMapper).deleteById(1L);
            verify(tokenCacheService).evictUserPermissions(10L);
        }

        @Test
        @DisplayName("deleteRole_notFound: 抛 BusinessException")
        void deleteRole_notFound() {
            when(roleMapper.selectById(999L)).thenReturn(null);
            assertThrows(BusinessException.class, () -> sysRoleService.deleteRole(999L));
        }
    }

    @Nested
    @DisplayName("分配权限")
    class AssignPermissionsTests {

        @Test
        @DisplayName("assignPermissions_success: 正常分配（含去重）")
        void assignPermissions_success() {
            SysRole role = new SysRole();
            role.setId(1L);
            when(roleMapper.selectById(1L)).thenReturn(role);

            SysPermission perm = new SysPermission();
            perm.setId(10L);
            when(permissionMapper.selectByIds(List.of(10L))).thenReturn(List.of(perm));

            doAnswer(invocation -> {
                java.util.function.Consumer<org.springframework.transaction.TransactionStatus> consumer = invocation.getArgument(0);
                consumer.accept(null);
                return null;
            }).when(transactionTemplate).executeWithoutResult(any());

            when(userRoleMapper.selectUserIdsByRoleId(1L)).thenReturn(List.of());

            assertDoesNotThrow(() -> sysRoleService.assignPermissions(1L, List.of(10L)));
            verify(rolePermissionMapper).deleteByRoleId(1L);
            verify(rolePermissionMapper).batchInsert(anyList());
        }

        @Test
        @DisplayName("assignPermissions_duplicatePermIds: 重复 permissionId 被去重")
        void assignPermissions_duplicatePermIds() {
            SysRole role = new SysRole();
            role.setId(1L);
            when(roleMapper.selectById(1L)).thenReturn(role);

            SysPermission perm = new SysPermission();
            perm.setId(10L);
            when(permissionMapper.selectByIds(List.of(10L))).thenReturn(List.of(perm));

            doAnswer(invocation -> {
                java.util.function.Consumer<org.springframework.transaction.TransactionStatus> consumer = invocation.getArgument(0);
                consumer.accept(null);
                return null;
            }).when(transactionTemplate).executeWithoutResult(any());

            when(userRoleMapper.selectUserIdsByRoleId(1L)).thenReturn(List.of());

            assertDoesNotThrow(() -> sysRoleService.assignPermissions(1L, List.of(10L, 10L)));
            verify(rolePermissionMapper).batchInsert(anyList());
        }

        @Test
        @DisplayName("assignPermissions_nonExistentPermission: 权限不存在时抛 BusinessException")
        void assignPermissions_nonExistentPermission() {
            SysRole role = new SysRole();
            role.setId(1L);
            when(roleMapper.selectById(1L)).thenReturn(role);
            when(permissionMapper.selectByIds(List.of(999L))).thenReturn(List.of());

            assertThrows(BusinessException.class,
                    () -> sysRoleService.assignPermissions(1L, List.of(999L)));
        }

        @Test
        @DisplayName("assignPermissions_roleNotFound: 角色不存在时抛 BusinessException")
        void assignPermissions_roleNotFound() {
            when(roleMapper.selectById(999L)).thenReturn(null);

            assertThrows(BusinessException.class,
                    () -> sysRoleService.assignPermissions(999L, List.of(10L)));
        }
    }
}
