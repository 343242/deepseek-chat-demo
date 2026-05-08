package com.demo.chat.user.service;

import com.demo.chat.user.entity.SysPermission;
import java.util.List;

public interface SysPermissionService {

    List<SysPermission> listPermissions();

    SysPermission createPermission(String permissionName, String permissionDesc,
                                   String resourceType, String resourceKey);

    void deletePermission(Long permissionId);
}
