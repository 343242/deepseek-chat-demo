package com.smart.rag.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("sys_role_permission")
public class SysRolePermission {
    private Long roleId;
    private Long permissionId;

    public Long getRoleId() { return roleId; }
    public void setRoleId(Long roleId) { this.roleId = roleId; }
    public Long getPermissionId() { return permissionId; }
    public void setPermissionId(Long permissionId) { this.permissionId = permissionId; }
}
