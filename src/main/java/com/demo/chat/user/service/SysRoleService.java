package com.demo.chat.user.service;

import com.demo.chat.user.dto.AssignPermissionsRequest;
import com.demo.chat.user.dto.RoleDetailVO;
import com.demo.chat.user.entity.SysPermission;
import com.demo.chat.user.entity.SysRole;
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
