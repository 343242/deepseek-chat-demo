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

    UserDeleteResult deleteUser(Long id);
}
