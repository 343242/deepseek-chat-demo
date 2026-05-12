package com.demo.chat.user.controller;

import com.demo.chat.common.response.GlobalResponse;
import com.demo.chat.user.dto.AssignPermissionsRequest;
import com.demo.chat.user.dto.CreateRoleRequest;
import com.demo.chat.user.dto.RoleDetailVO;
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
    public GlobalResponse<List<SysRole>> listRoles() {
        return GlobalResponse.ok(roleService.listRoles());
    }

    @GetMapping("/{id}")
    public GlobalResponse<RoleDetailVO> getRole(@PathVariable Long id) {
        return GlobalResponse.ok(roleService.getRoleDetail(id));
    }

    @PostMapping
    public GlobalResponse<SysRole> createRole(@Valid @RequestBody CreateRoleRequest request) {
        return GlobalResponse.ok(roleService.createRole(request.roleName(), request.roleDesc()));
    }

    @PutMapping("/{id}")
    public GlobalResponse<SysRole> updateRole(@PathVariable Long id, @Valid @RequestBody UpdateRoleRequest request) {
        return GlobalResponse.ok(roleService.updateRole(id, request.roleDesc()));
    }

    @DeleteMapping("/{id}")
    public GlobalResponse<Void> deleteRole(@PathVariable Long id) {
        roleService.deleteRole(id);
        return GlobalResponse.ok("角色已删除");
    }

    @GetMapping("/{id}/permissions")
    public GlobalResponse<List<SysPermission>> getRolePermissions(@PathVariable Long id) {
        return GlobalResponse.ok(roleService.getRolePermissions(id));
    }

    @PatchMapping("/{id}/permissions")
    public GlobalResponse<Map<String, Object>> assignPermissions(@PathVariable Long id,
                                                                   @Valid @RequestBody AssignPermissionsRequest request) {
        roleService.assignPermissions(id, request.permissionIds());
        return GlobalResponse.ok(Map.of(
                "roleId", id,
                "permissionIds", request.permissionIds(),
                "message", "权限已更新"
        ));
    }

    @GetMapping("/permissions")
    public GlobalResponse<List<SysPermission>> listAllPermissions() {
        return GlobalResponse.ok(permissionService.listPermissions());
    }
}
