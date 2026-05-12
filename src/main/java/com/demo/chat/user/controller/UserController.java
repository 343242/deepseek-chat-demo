package com.demo.chat.user.controller;

import com.demo.chat.common.response.GlobalResponse;
import com.demo.chat.user.dto.AssignRolesRequest;
import com.demo.chat.user.dto.LoginResponse;
import com.demo.chat.user.dto.UserUpdateRequest;
import com.demo.chat.user.service.SysUserService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用户管理控制器 — 仅负责 HTTP 请求/响应的转发
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
    public GlobalResponse<Map<String, Object>> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        return GlobalResponse.ok(sysUserService.listUsers(page, size, keyword));
    }

    @GetMapping("/{id}")
    public GlobalResponse<LoginResponse.UserInfo> getUser(@PathVariable Long id) {
        return GlobalResponse.ok(sysUserService.getUser(id));
    }

    @PatchMapping("/{id}")
    public GlobalResponse<LoginResponse.UserInfo> updateUser(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest request) {
        return GlobalResponse.ok(sysUserService.updateUser(id, request));
    }

    @PatchMapping("/{id}/status")
    public GlobalResponse<Map<String, Object>> updateUserStatus(@PathVariable Long id, @RequestParam Integer status) {
        return GlobalResponse.ok(sysUserService.updateUserStatus(id, status));
    }

    @PatchMapping("/{id}/roles")
    public GlobalResponse<Map<String, Object>> assignRoles(@PathVariable Long id, @Valid @RequestBody AssignRolesRequest request) {
        return GlobalResponse.ok(sysUserService.assignRoles(id, request));
    }

    @DeleteMapping("/{id}")
    public GlobalResponse<Map<String, Object>> deleteUser(@PathVariable Long id) {
        return GlobalResponse.ok(sysUserService.deleteUser(id));
    }
}
