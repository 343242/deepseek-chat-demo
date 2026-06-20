package com.smart.rag.user.service;

import com.smart.rag.infrastructure.response.PagedResult;
import com.smart.rag.user.dto.*;

/**
 * 用户管理服务接口
 */
public interface SysUserService {

    PagedResult<UserVO> listUsers(int page, int size, String keyword);

    LoginResponse.UserInfo getUser(Long id);

    LoginResponse.UserInfo updateUser(Long id, UserUpdateRequest request);

    UserStatusUpdateResult updateUserStatus(Long id, Integer status);

    RoleAssignResult assignRoles(Long id, AssignRolesRequest request);

    /** 清空用户的全部角色（显式操作，区别于 assign 的 @NotEmpty 护栏；管理员谨慎使用，避免清空自身角色导致失权） */
    RoleAssignResult clearRoles(Long id);

    UserDeleteResult deleteUser(Long id);
}
