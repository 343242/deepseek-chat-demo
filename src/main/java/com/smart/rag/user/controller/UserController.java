package com.smart.rag.user.controller;

import com.smart.rag.infrastructure.response.GlobalResponse;
import com.smart.rag.infrastructure.response.PagedResult;
import com.smart.rag.user.dto.*;
import com.smart.rag.user.service.SysUserService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 用户管理控制器
 */
@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasAuthority('user:manage')")
public class UserController {

    private final SysUserService sysUserService;

    public UserController(SysUserService sysUserService) {
        this.sysUserService = sysUserService;
    }

    @GetMapping
    public GlobalResponse<PagedResult<UserVO>> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        return GlobalResponse.ok(sysUserService.listUsers(page, size, keyword));
    }

    @GetMapping("/{id}")
    public GlobalResponse<LoginResponse.UserInfo> getUser(@PathVariable Long id) {
        return GlobalResponse.ok(sysUserService.getUser(id));
    }

    @PostMapping("/{id}/update")
    public GlobalResponse<LoginResponse.UserInfo> updateUser(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest request) {
        return GlobalResponse.ok(sysUserService.updateUser(id, request));
    }

    @PostMapping("/{id}/status")
    public GlobalResponse<UserStatusUpdateResult> updateUserStatus(@PathVariable Long id, @RequestParam Integer status) {
        return GlobalResponse.ok(sysUserService.updateUserStatus(id, status));
    }

    @PostMapping("/{id}/roles")
    public GlobalResponse<RoleAssignResult> assignRoles(@PathVariable Long id, @Valid @RequestBody AssignRolesRequest request) {
        return GlobalResponse.ok(sysUserService.assignRoles(id, request));
    }

    @PostMapping("/{id}/roles/clear")
    public GlobalResponse<RoleAssignResult> clearRoles(@PathVariable Long id) {
        return GlobalResponse.ok(sysUserService.clearRoles(id));
    }

    @PostMapping("/{id}/delete")
    public GlobalResponse<UserDeleteResult> deleteUser(@PathVariable Long id) {
        return GlobalResponse.ok(sysUserService.deleteUser(id));
    }
}
