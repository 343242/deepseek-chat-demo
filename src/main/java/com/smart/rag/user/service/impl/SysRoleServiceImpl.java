package com.smart.rag.user.service.impl;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.infrastructure.web.service.TokenCacheService;
import com.smart.rag.user.dto.PermissionVO;
import com.smart.rag.user.dto.RoleDetailVO;
import com.smart.rag.user.dto.RoleVO;
import com.smart.rag.user.entity.SysPermission;
import com.smart.rag.user.entity.SysRole;
import com.smart.rag.user.entity.SysRolePermission;
import com.smart.rag.user.mapper.SysPermissionMapper;
import com.smart.rag.user.mapper.SysRoleMapper;
import com.smart.rag.user.mapper.SysRolePermissionMapper;
import com.smart.rag.user.mapper.SysUserRoleMapper;
import com.smart.rag.user.service.SysRoleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class SysRoleServiceImpl implements SysRoleService {

    private final SysRoleMapper roleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysPermissionMapper permissionMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final TokenCacheService tokenCacheService;
    private final TransactionTemplate transactionTemplate;

    public SysRoleServiceImpl(SysRoleMapper roleMapper,
                          SysRolePermissionMapper rolePermissionMapper,
                          SysPermissionMapper permissionMapper,
                          SysUserRoleMapper userRoleMapper,
                          TokenCacheService tokenCacheService,
                          TransactionTemplate transactionTemplate) {
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.permissionMapper = permissionMapper;
        this.userRoleMapper = userRoleMapper;
        this.tokenCacheService = tokenCacheService;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public List<RoleVO> listRoles() {
        return roleMapper.selectAllOrdered().stream()
                .map(SysRoleServiceImpl::toRoleVO)
                .toList();
    }

    @Override
    public RoleDetailVO getRoleDetail(Long roleId) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new ServiceException(ServiceErrorCode.ROLE_NOT_FOUND);
        }
        List<PermissionVO> permissions = rolePermissionMapper.selectPermissionsByRoleId(roleId).stream()
                .map(SysRoleServiceImpl::toPermissionVO)
                .toList();
        return new RoleDetailVO(toRoleVO(role), permissions);
    }

    @Override
    public RoleVO createRole(String roleName, String roleDesc) {
        roleMapper.selectByRoleName(roleName)
                .ifPresent(existing -> { throw new ClientException(ClientErrorCode.ROLE_NAME_EXISTS); });

        SysRole role = new SysRole();
        role.setRoleName(roleName);
        role.setRoleDesc(roleDesc);
        role.setStatus(1);
        roleMapper.insert(role);
        return toRoleVO(role);
    }

    @Override
    public RoleVO updateRole(Long roleId, String roleDesc) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new ServiceException(ServiceErrorCode.ROLE_NOT_FOUND);
        }
        role.setRoleDesc(roleDesc);
        roleMapper.updateById(role);
        return toRoleVO(role);
    }

    @Override
    public void deleteRole(Long roleId) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new ServiceException(ServiceErrorCode.ROLE_NOT_FOUND);
        }

        // 提交前捕获受影响用户（删完 sys_user_role 绑定后查不到），提交后再驱逐权限缓存
        List<Long> userIds = userRoleMapper.selectUserIdsByRoleId(roleId);

        transactionTemplate.executeWithoutResult(status -> {
            rolePermissionMapper.deleteByRoleId(roleId);
            userRoleMapper.deleteByRoleId(roleId);
            roleMapper.deleteById(roleId);
        });

        userIds.forEach(tokenCacheService::evictUserPermissions);
    }

    @Override
    public void assignPermissions(Long roleId, List<Long> permissionIds) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new ServiceException(ServiceErrorCode.ROLE_NOT_FOUND);
        }

        List<Long> uniquePermIds = permissionIds.stream().distinct().toList();

        transactionTemplate.executeWithoutResult(status -> {
            // 存在性校验放入事务，关闭 TOCTOU（校验与写入原子）
            List<SysPermission> existingPerms = permissionMapper.selectByIds(uniquePermIds);
            if (existingPerms.size() != uniquePermIds.size()) {
                Set<Long> found = existingPerms.stream().map(SysPermission::getId).collect(Collectors.toSet());
                List<Long> missing = uniquePermIds.stream().filter(pid -> !found.contains(pid)).toList();
                throw new ServiceException(ServiceErrorCode.PERMISSION_NOT_FOUND, "权限不存在: " + missing);
            }
            rolePermissionMapper.deleteByRoleId(roleId);

            List<SysRolePermission> bindings = uniquePermIds.stream()
                    .map(permId -> {
                        SysRolePermission rp = new SysRolePermission();
                        rp.setRoleId(roleId);
                        rp.setPermissionId(permId);
                        return rp;
                    })
                    .toList();
            if (!bindings.isEmpty()) {
                rolePermissionMapper.batchInsert(bindings);
            }
        });

        List<Long> userIds = userRoleMapper.selectUserIdsByRoleId(roleId);
        for (Long userId : userIds) {
            tokenCacheService.evictUserPermissions(userId);
        }
    }

    @Override
    public List<PermissionVO> getRolePermissions(Long roleId) {
        return rolePermissionMapper.selectPermissionsByRoleId(roleId).stream()
                .map(SysRoleServiceImpl::toPermissionVO)
                .toList();
    }

    // ==================== Entity → VO 转换 ====================

    private static RoleVO toRoleVO(SysRole role) {
        return new RoleVO(role.getId(), role.getRoleName(), role.getRoleDesc(),
                role.getStatus(), role.getCreatedAt(), role.getUpdatedAt());
    }

    private static PermissionVO toPermissionVO(SysPermission p) {
        return new PermissionVO(p.getId(), p.getPermissionName(), p.getPermissionDesc(),
                p.getResourceType(), p.getResourceKey(), p.getParentId(), p.getStatus(),
                p.getCreatedAt(), p.getUpdatedAt());
    }
}
