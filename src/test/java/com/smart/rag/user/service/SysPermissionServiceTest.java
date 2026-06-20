package com.smart.rag.user.service;

import com.smart.rag.user.service.impl.SysPermissionServiceImpl;
import com.smart.rag.user.dto.PermissionVO;
import com.smart.rag.user.entity.SysPermission;
import com.smart.rag.user.mapper.SysPermissionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SysPermissionService 单元测试")
class SysPermissionServiceTest {

    @Mock
    private SysPermissionMapper permissionMapper;

    @InjectMocks
    private SysPermissionServiceImpl sysPermissionService;

    @Test
    @DisplayName("listPermissions_success: 返回列表")
    void listPermissions_success() {
        SysPermission perm = new SysPermission();
        perm.setId(1L);
        perm.setPermissionName("user:read");
        when(permissionMapper.selectAllOrdered()).thenReturn(List.of(perm));

        List<PermissionVO> result = sysPermissionService.listPermissions();

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("user:read", result.get(0).permissionName());
    }
}
