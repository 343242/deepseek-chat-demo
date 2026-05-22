package com.smart.rag.user.service.impl;

import com.smart.rag.common.errorcode.ErrorCode;
import com.smart.rag.common.request.PageRequest;
import com.smart.rag.common.response.PagedResult;
import com.smart.rag.exception.BusinessException;
import com.smart.rag.user.dto.*;
import com.smart.rag.user.enums.UserStatus;
import com.smart.rag.security.service.TokenCacheService;
import com.smart.rag.user.entity.SysRole;
import com.smart.rag.user.entity.SysUser;
import com.smart.rag.user.entity.SysUserRole;
import com.smart.rag.user.mapper.SysRoleMapper;
import com.smart.rag.user.mapper.SysUserMapper;
import com.smart.rag.user.mapper.SysUserRoleMapper;
import com.smart.rag.user.service.AuthService;
import com.smart.rag.user.service.SysUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 用户管理 Service
 */
@Service
public class SysUserServiceImpl implements SysUserService {

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final TransactionTemplate transactionTemplate;
    private final TokenCacheService tokenCacheService;
    private final AuthService authService;

    public SysUserServiceImpl(SysUserMapper userMapper,
                              SysUserRoleMapper userRoleMapper,
                              SysRoleMapper roleMapper,
                              TransactionTemplate transactionTemplate,
                              TokenCacheService tokenCacheService,
                              AuthService authService) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.transactionTemplate = transactionTemplate;
        this.tokenCacheService = tokenCacheService;
        this.authService = authService;
    }

    @Override
    public PagedResult<UserVO> listUsers(int page, int size, String keyword) {
        PageRequest req = PageRequest.of(page, size);
        var pageResult = userMapper.selectPage(req.toPage(),
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getDeleted, 0)
                        .and(keyword != null && !keyword.isBlank(), w ->
                                w.like(SysUser::getUsername, keyword)
                                        .or().like(SysUser::getNickname, keyword))
                        .orderByDesc(SysUser::getCreatedAt));

        return PagedResult.of(pageResult, SysUserServiceImpl::toUserVO);
    }

    @Override
    public LoginResponse.UserInfo getUser(Long id) {
        return authService.getCurrentUser(id);
    }

    @Override
    public LoginResponse.UserInfo updateUser(Long id, UserUpdateRequest request) {
        return authService.updateProfile(id, request);
    }

    @Override
    public UserStatusUpdateResult updateUserStatus(Long id, Integer status) {
        if (!UserStatus.isValid(status)) {
            throw new BusinessException(ErrorCode.USER_STATUS_INVALID);
        }
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        user.setStatus(status);
        userMapper.updateById(user);

        if (status == UserStatus.DISABLED.code) {
            authService.revokeAllUserTokens(id);
        } else if (status == UserStatus.ENABLED.code) {
            tokenCacheService.clearUserStatus(id);
        }

        return new UserStatusUpdateResult(id, status, status == UserStatus.ENABLED.code ? "已启用" : "已禁用");
    }

    @Override
    public RoleAssignResult assignRoles(Long id, AssignRolesRequest request) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        List<Long> uniqueRoleIds = request.roleIds().stream().distinct().toList();

        List<SysRole> existingRoles = roleMapper.selectByIds(uniqueRoleIds);
        if (existingRoles.size() != uniqueRoleIds.size()) {
            Set<Long> found = existingRoles.stream().map(SysRole::getId).collect(Collectors.toSet());
            List<Long> missing = uniqueRoleIds.stream().filter(rid -> !found.contains(rid)).toList();
            throw new BusinessException(ErrorCode.ROLE_NOT_FOUND, "角色不存在: " + missing);
        }

        transactionTemplate.executeWithoutResult(status -> {
            userRoleMapper.deleteByUserId(id);
            List<SysUserRole> bindings = uniqueRoleIds.stream()
                    .map(roleId -> {
                        SysUserRole userRole = new SysUserRole();
                        userRole.setUserId(id);
                        userRole.setRoleId(roleId);
                        return userRole;
                    })
                    .toList();
            if (!bindings.isEmpty()) {
                userRoleMapper.batchInsert(bindings);
            }
        });

        tokenCacheService.evictUserPermissions(id);

        return new RoleAssignResult(id, uniqueRoleIds, "角色已更新");
    }

    @Override
    public UserDeleteResult deleteUser(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        userMapper.deleteById(id);
        authService.revokeAllUserTokens(id);

        return new UserDeleteResult(id, "用户已删除");
    }

    // ==================== 转换方法 ====================

    private static UserVO toUserVO(SysUser user) {
        return new UserVO(
                user.getId(), user.getUsername(), user.getNickname(),
                user.getEmail(), user.getPhone(), user.getAvatar(),
                user.getStatus(), user.getCreatedAt(), user.getUpdatedAt()
        );
    }
}
