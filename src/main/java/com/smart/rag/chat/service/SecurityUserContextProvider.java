package com.smart.rag.chat.service;

import com.smart.rag.infrastructure.web.util.SecurityUtils;
import org.springframework.stereotype.Component;

/**
 * 基于 Spring Security 的用户上下文提供者实现
 */
@Component
public class SecurityUserContextProvider implements UserContextProvider {

    @Override
    public Long getCurrentUserId() {
        return SecurityUtils.getCurrentUserId();
    }
}
