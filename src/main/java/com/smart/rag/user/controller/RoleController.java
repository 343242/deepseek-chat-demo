package com.smart.rag.user.controller;

import com.smart.rag.infrastructure.response.GlobalResponse;
import com.smart.rag.user.dto.AssignPermissionsResult;
import com.smart.rag.user.dto.AssignPermissionsRequest;
import com.smart.rag.user.dto.CreateRoleRequest;
import com.smart.rag.user.dto.PermissionVO;
import com.smart.rag.user.dto.RoleDetailVO;
import com.smart.rag.user.dto.RoleVO;
import com.smart.rag.user.dto.UpdateRoleRequest;
import com.smart.rag.user.service.SysPermissionService;
import com.smart.rag.user.service.SysRoleService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    public GlobalResponse<List<RoleVO>> listRoles() {
        return GlobalResponse.ok(roleService.listRoles());
    }

    @GetMapping("/{id}")
    public GlobalResponse<RoleDetailVO> getRole(@PathVariable Long id) {
        return GlobalResponse.ok(roleService.getRoleDetail(id));
    }

    @PostMapping
    public GlobalResponse<RoleVO> createRole(@Valid @RequestBody CreateRoleRequest request) {
        return GlobalResponse.ok(roleService.createRole(request.roleName(), request.roleDesc()));
    }

    @PostMapping("/{id}/update")
    public GlobalResponse<RoleVO> updateRole(@PathVariable Long id, @Valid @RequestBody UpdateRoleRequest request) {
        return GlobalResponse.ok(roleService.updateRole(id, request.roleDesc()));
    }

    @PostMapping("/{id}/delete")
    public GlobalResponse<Void> deleteRole(@PathVariable Long id) {
        roleService.deleteRole(id);
        return GlobalResponse.ok("角色已删除");
    }

    @GetMapping("/{id}/permissions")
    public GlobalResponse<List<PermissionVO>> getRolePermissions(@PathVariable Long id) {
        return GlobalResponse.ok(roleService.getRolePermissions(id));
    }

    @PostMapping("/{id}/permissions/update")
    public GlobalResponse<AssignPermissionsResult> assignPermissions(@PathVariable Long id,
                                                                       @Valid @RequestBody AssignPermissionsRequest request) {
        roleService.assignPermissions(id, request.permissionIds());
        return GlobalResponse.ok(new AssignPermissionsResult(id, request.permissionIds(), "权限已更新"));
    }

    @PostMapping("/{id}/permissions/clear")
    public GlobalResponse<AssignPermissionsResult> clearPermissions(@PathVariable Long id) {
        return GlobalResponse.ok(roleService.clearPermissions(id));
    }

    @GetMapping("/permissions")
    public GlobalResponse<List<PermissionVO>> listAllPermissions() {
        return GlobalResponse.ok(permissionService.listPermissions());
    }
}
