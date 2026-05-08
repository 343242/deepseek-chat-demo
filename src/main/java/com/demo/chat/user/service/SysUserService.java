package com.demo.chat.user.service;

import com.demo.chat.user.dto.AssignRolesRequest;
import com.demo.chat.user.dto.LoginResponse;
import com.demo.chat.user.dto.UserUpdateRequest;
import java.util.Map;

public interface SysUserService {

    Map<String, Object> listUsers(int page, int size, String keyword);

    LoginResponse.UserInfo getUser(Long id);

    LoginResponse.UserInfo updateUser(Long id, UserUpdateRequest request);

    Map<String, Object> updateUserStatus(Long id, Integer status);

    Map<String, Object> assignRoles(Long id, AssignRolesRequest request);

    Map<String, Object> deleteUser(Long id);
}
