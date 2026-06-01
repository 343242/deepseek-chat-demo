package com.smart.rag.infrastructure.web.auth;

import java.util.Set;

public interface UserPermissionProvider {

    /**
     * P0-1: return permissionName values used as Spring Security GrantedAuthority names.
     */
    Set<String> loadUserPermissions(Long userId);
}
