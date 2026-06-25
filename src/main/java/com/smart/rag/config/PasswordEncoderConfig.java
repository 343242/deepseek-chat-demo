package com.smart.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码编码器配置（独立于 SecurityConfig，避免循环依赖）
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // cost=10：Spring Security 默认值、OWASP 推荐下限。
        // cost 每增 1 校验耗时翻倍，12→10 砍约 75% 耗时（登录 BCrypt 占 80–93%，是延迟主因）。
        // 已有 cost=12 的 hash 在 matches() 时按 hash 内嵌 cost 验证，零影响；新注册/改密用 cost=10。
        return new BCryptPasswordEncoder(10);
    }
}
