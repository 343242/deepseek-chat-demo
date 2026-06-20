package com.smart.rag.user.service;

import com.smart.rag.user.dto.PermissionVO;
import com.smart.rag.user.dto.RoleDetailVO;
import com.smart.rag.user.dto.RoleVO;
import java.util.List;

public interface SysRoleService {

    List<RoleVO> listRoles();

    RoleDetailVO getRoleDetail(Long roleId);

    RoleVO createRole(String roleName, String roleDesc);

    RoleVO updateRole(Long roleId, String roleDesc);

    void deleteRole(Long roleId);

    void assignPermissions(Long roleId, List<Long> permissionIds);

    List<PermissionVO> getRolePermissions(Long roleId);
}
