package com.demo.deepseekchat.user.controller;

import com.demo.deepseekchat.user.dto.AssignRolesRequest;
import com.demo.deepseekchat.user.dto.LoginResponse;
import com.demo.deepseekchat.user.dto.UserUpdateRequest;
import com.demo.deepseekchat.user.service.SysUserService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

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
        if (!com.demo.deepseekchat.user.enums.UserStatus.isValid(status)) {
            throw new com.demo.deepseekchat.exception.BusinessException("无效的用户状态，仅支持 0(禁用) 和 1(启用)");
        }
        return sysUserService.updateUserStatus(id, status);
    }

    @PatchMapping("/{id}/roles")
    public Map<String, Object> assignRoles(@PathVariable Long id, @RequestBody AssignRolesRequest request) {
        return sysUserService.assignRoles(id, request);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteUser(@PathVariable Long id) {
        return sysUserService.deleteUser(id);
    }
}
