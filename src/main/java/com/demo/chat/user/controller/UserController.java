package com.demo.chat.user.controller;

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
 *
 * <p>业务校验（如 UserStatus 合法性）已下沉到 Service 层。</p>
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
    public Map<String, Object> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        return sysUserService.listUsers(page, size, keyword);
    }

    @GetMapping("/{id}")
    public LoginResponse.UserInfo getUser(@PathVariable Long id) {
        return sysUserService.getUser(id);
    }

    @PatchMapping("/{id}")
    public LoginResponse.UserInfo updateUser(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest request) {
        return sysUserService.updateUser(id, request);
    }

    @PatchMapping("/{id}/status")
    public Map<String, Object> updateUserStatus(@PathVariable Long id, @RequestParam Integer status) {
        return sysUserService.updateUserStatus(id, status);
    }

    @PatchMapping("/{id}/roles")
    public Map<String, Object> assignRoles(@PathVariable Long id, @Valid @RequestBody AssignRolesRequest request) {
        return sysUserService.assignRoles(id, request);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteUser(@PathVariable Long id) {
        return sysUserService.deleteUser(id);
    }
}
