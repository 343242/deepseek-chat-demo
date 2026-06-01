package com.smart.rag.user.service;

import com.smart.rag.infrastructure.web.service.TokenCacheService;
import com.smart.rag.user.entity.SysPermission;
import com.smart.rag.user.mapper.SysRolePermissionMapper;
import com.smart.rag.user.mapper.SysUserRoleMapper;
import com.smart.rag.user.service.impl.DatabaseUserPermissionProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DatabaseUserPermissionProvider")
class DatabaseUserPermissionProviderTest {

    @Mock private SysUserRoleMapper sysUserRoleMapper;
    @Mock private SysRolePermissionMapper sysRolePermissionMapper;
    @Mock private TokenCacheService tokenCacheService;

    @InjectMocks
    private DatabaseUserPermissionProvider provider;

    @Test
    @DisplayName("loads permissionName values and caches them")
    void loadUserPermissions_loadsPermissionNamesAndCachesThem() {
        SysPermission read = permission("conversation:read");
        SysPermission write = permission("conversation:write");
        SysPermission unnamed = permission(null);

        when(sysUserRoleMapper.selectRoleIdsByUserId(1L)).thenReturn(List.of(10L, 20L));
        when(sysRolePermissionMapper.selectPermissionsByRoleIds(List.of(10L, 20L)))
                .thenReturn(List.of(read, write, unnamed));

        Set<String> result = provider.loadUserPermissions(1L);

        assertThat(result).containsExactlyInAnyOrder("conversation:read", "conversation:write");
        verify(tokenCacheService).cacheUserPermissions(1L, result);
    }

    @Test
    @DisplayName("caches empty permission set when user has no roles")
    void loadUserPermissions_noRolesCachesEmptySet() {
        when(sysUserRoleMapper.selectRoleIdsByUserId(1L)).thenReturn(List.of());

        Set<String> result = provider.loadUserPermissions(1L);

        assertThat(result).isEmpty();
        verify(tokenCacheService).cacheUserPermissions(1L, Set.of());
    }

    private static SysPermission permission(String name) {
        SysPermission permission = new SysPermission();
        permission.setPermissionName(name);
        return permission;
    }
}
