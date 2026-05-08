package com.demo.chat.user.controller;

import com.demo.chat.user.dto.AssignPermissionsRequest;
import com.demo.chat.user.dto.CreateRoleRequest;
import com.demo.chat.user.dto.UpdateRoleRequest;
import com.demo.chat.user.entity.SysPermission;
import com.demo.chat.user.entity.SysRole;
import com.demo.chat.user.service.SysPermissionService;
import com.demo.chat.user.service.SysRoleService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 角色管理控制器 — 仅负责 HTTP 请求/响应的转发
 *
 * <p>所有请求参数使用专用 DTO 承载，确保验证完整。</p>
 */
@RestController
@RequestMapping("/api/roles")
@PreAuthorize("hasAuthority('role:manage')")
public class RoleController {

    private final SysRoleService roleService;
    private final SysPermissionService permissionService;

    public RoleController(SysRoleService roleService, SysPermissionService permissionService) {
        this.roleService = roleService;
        this.permissionService = permissionService;
    }

    @GetMapping
    public List<SysRole> listRoles() {
        return roleService.listRoles();
    }

    @GetMapping("/{id}")
    public Map<String, Object> getRole(@PathVariable Long id) {
        return roleService.getRoleDetail(id);
    }

    @PostMapping
    public SysRole createRole(@Valid @RequestBody CreateRoleRequest request) {
        return roleService.createRole(request.roleName(), request.roleDesc());
    }

    @PutMapping("/{id}")
    public SysRole updateRole(@PathVariable Long id, @Valid @RequestBody UpdateRoleRequest request) {
        return roleService.updateRole(id, request.roleDesc());
    }

    @DeleteMapping("/{id}")
    public Map<String, String> deleteRole(@PathVariable Long id) {
        roleService.deleteRole(id);
        return Map.of("roleId", String.valueOf(id), "message", "角色已删除");
    }

    @GetMapping("/{id}/permissions")
    public List<SysPermission> getRolePermissions(@PathVariable Long id) {
        return roleService.getRolePermissions(id);
    }

    @PatchMapping("/{id}/permissions")
    public Map<String, Object> assignPermissions(@PathVariable Long id,
                                                  @Valid @RequestBody AssignPermissionsRequest request) {
        roleService.assignPermissions(id, request.permissionIds());
        return Map.of("roleId", id, "permissionIds", request.permissionIds(), "message", "权限已更新");
    }

    @GetMapping("/permissions")
    public List<SysPermission> listAllPermissions() {
        return permissionService.listPermissions();
    }
}
