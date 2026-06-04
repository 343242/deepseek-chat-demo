package com.smart.rag.user.service;

import com.smart.rag.user.service.impl.SysPermissionServiceImpl;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.user.entity.SysPermission;
import com.smart.rag.user.mapper.SysPermissionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SysPermissionService 单元测试")
class SysPermissionServiceTest {

    @Mock
    private SysPermissionMapper permissionMapper;

    @InjectMocks
    private SysPermissionServiceImpl sysPermissionService;

    // ==================== Create ====================

    @Test
    @DisplayName("createPermission_success: 正常创建")
    void createPermission_success() {
        when(permissionMapper.selectByPermissionName("user:read")).thenReturn(Optional.empty());
        when(permissionMapper.selectByResourceKey("api:user:read")).thenReturn(Optional.empty());
        when(permissionMapper.insert(any(SysPermission.class))).thenReturn(1);

        SysPermission result = sysPermissionService.createPermission("user:read", "查看用户", "API", "api:user:read");

        assertNotNull(result);
        verify(permissionMapper).insert(any(SysPermission.class));
    }

    @Test
    @DisplayName("createPermission_duplicateResourceKey: resourceKey 重复时抛 ClientException")
    void createPermission_duplicateResourceKey() {
        SysPermission existing = new SysPermission();
        existing.setId(1L);
        existing.setResourceKey("api:user:read");
        when(permissionMapper.selectByPermissionName("user:write")).thenReturn(Optional.empty());
        when(permissionMapper.selectByResourceKey("api:user:read")).thenReturn(Optional.of(existing));

        ClientException ex = assertThrows(ClientException.class,
                () -> sysPermissionService.createPermission("user:write", "写入用户", "API", "api:user:read"));
        assertTrue(ex.getMessage().contains("权限标识已存在"));
        verify(permissionMapper, never()).insert(any(SysPermission.class));
    }

    @Test
    @DisplayName("createPermission_duplicatePermissionName: permissionName 重复时抛 ClientException")
    void createPermission_duplicatePermissionName() {
        SysPermission existing = new SysPermission();
        existing.setId(1L);
        existing.setPermissionName("user:read");
        when(permissionMapper.selectByPermissionName("user:read")).thenReturn(Optional.of(existing));

        ClientException ex = assertThrows(ClientException.class,
                () -> sysPermissionService.createPermission("user:read", "查看用户", "API", "api:user:view"));
        assertTrue(ex.getMessage().contains("权限名称已存在"));
        verify(permissionMapper, never()).insert(any(SysPermission.class));
    }

    // ==================== Delete ====================

    @Test
    @DisplayName("deletePermission_success: 正常删除")
    void deletePermission_success() {
        SysPermission perm = new SysPermission();
        perm.setId(1L);
        perm.setPermissionName("user:read");
        when(permissionMapper.selectById(1L)).thenReturn(perm);
        when(permissionMapper.deleteById(1L)).thenReturn(1);

        assertDoesNotThrow(() -> sysPermissionService.deletePermission(1L));
        verify(permissionMapper).deleteById(1L);
    }

    @Test
    @DisplayName("deletePermission_notFound: 抛 ServiceException")
    void deletePermission_notFound() {
        when(permissionMapper.selectById(999L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> sysPermissionService.deletePermission(999L));
        assertTrue(ex.getMessage().contains("权限不存在"));
    }

    // ==================== List ====================

    @Test
    @DisplayName("listPermissions_success: 返回列表")
    void listPermissions_success() {
        SysPermission perm = new SysPermission();
        perm.setId(1L);
        perm.setPermissionName("user:read");
        when(permissionMapper.selectAllOrdered()).thenReturn(List.of(perm));

        List<SysPermission> result = sysPermissionService.listPermissions();

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("user:read", result.get(0).getPermissionName());
    }
}
