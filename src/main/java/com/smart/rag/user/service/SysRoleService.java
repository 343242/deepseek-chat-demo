package com.smart.rag.user.service;

import com.smart.rag.user.dto.RoleDetailVO;
import com.smart.rag.user.entity.SysPermission;
import com.smart.rag.user.entity.SysRole;
import java.util.List;

public interface SysRoleService {

    List<SysRole> listRoles();

    RoleDetailVO getRoleDetail(Long roleId);

    SysRole createRole(String roleName, String roleDesc);

    SysRole updateRole(Long roleId, String roleDesc);

    void deleteRole(Long roleId);

    void assignPermissions(Long roleId, List<Long> permissionIds);

    List<SysPermission> getRolePermissions(Long roleId);
}
