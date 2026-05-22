package com.smart.rag.user.service;

import com.smart.rag.user.dto.LoginResponse;
import com.smart.rag.user.dto.UserUpdateRequest;

import java.util.Set;

public interface AuthService {

    record TokenPair(String accessToken, String refreshToken) {}

    /**
     * Login: validates credentials and returns token pair + user info.
     * Caller (controller) is responsible for setting cookies.
     */
    record LoginResult(TokenPair tokens, LoginResponse response) {}

    LoginResult login(String username, String password, String ip,
                      String captchaId, String captchaCode);

    LoginResponse.UserInfo register(String username, String password, String email,
                                    String nickname, String captchaId, String captchaCode);

    LoginResult refreshToken(String refreshToken);

    void logout(Long userId, String accessToken);

    LoginResponse.UserInfo getCurrentUser(Long userId);

    void changePassword(Long userId, String oldPassword, String newPassword);

    void revokeAllUserTokens(Long userId);

    /**
     * P0-1 修复：使用 permissionName 而非 resourceKey 作为 GrantedAuthority
     */
    Set<String> loadUserPermissions(Long userId);

    LoginResponse.UserInfo updateProfile(Long userId, UserUpdateRequest request);
}
