package com.smart.rag.user.service;

import com.smart.rag.user.entity.SysPermission;
import java.util.List;

public interface SysPermissionService {

    List<SysPermission> listPermissions();

    SysPermission createPermission(String permissionName, String permissionDesc,
                                   String resourceType, String resourceKey);

    void deletePermission(Long permissionId);
}
