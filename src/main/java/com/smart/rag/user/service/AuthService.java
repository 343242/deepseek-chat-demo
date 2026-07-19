package com.smart.rag.user.service;

import com.smart.rag.user.dto.LoginResponse;
import com.smart.rag.user.dto.UserUpdateRequest;

public interface AuthService {

    record TokenPair(String accessToken, String refreshToken) {}

    /**
     * Login: validates credentials and returns token pair + user info.
     * Caller (controller) is responsible for setting cookies.
     */
    record LoginResult(TokenPair tokens, LoginResponse response) {}

    LoginResult login(String username, String password, String ip,
                      String captchaId, String captchaCode);

    /**
     * 注册并在 DB 事务提交后直接签发 token，实现"注册即登录"。
     * <p>
     * 与 {@link #login} 行为对齐：返回 {@link LoginResult}（含 token pair + UserInfo），
     * 由 Controller 负责写入 access/refresh Cookie。
     */
    LoginResult register(String username, String password, String email,
                         String nickname, String captchaId, String captchaCode, String ip);

    LoginResult refreshToken(String refreshToken);

    /**
     * 注销：撤销该用户的全部会话/设备（access + refresh token 全部清空，权限缓存一并驱逐）。
     */
    void logout(Long userId);

    LoginResponse.UserInfo getCurrentUser(Long userId);

    void changePassword(Long userId, String oldPassword, String newPassword);

    void revokeAllUserTokens(Long userId);

    LoginResponse.UserInfo updateProfile(Long userId, UserUpdateRequest request);
}
