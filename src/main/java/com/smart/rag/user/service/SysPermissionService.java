package com.smart.rag.user.service;

import com.smart.rag.user.dto.PermissionVO;
import java.util.List;

public interface SysPermissionService {

    List<PermissionVO> listPermissions();

    PermissionVO createPermission(String permissionName, String permissionDesc,
                                   String resourceType, String resourceKey);

    void deletePermission(Long permissionId);
}
