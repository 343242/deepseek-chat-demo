package com.demo.chat.user.service;

import com.demo.chat.common.response.PagedResult;
import com.demo.chat.user.dto.*;
import org.apache.ibatis.annotations.Param;

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
