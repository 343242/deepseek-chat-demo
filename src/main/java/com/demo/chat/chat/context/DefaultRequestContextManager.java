package com.demo.chat.chat.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 默认请求上下文管理器
 * <p>
 * 组合三个策略解析器（用户画像、会话上下文、策略约束），
 * 各维度独立收集，互不耦合。
 * <p>
 * 降级策略：任何 Resolver 失败不阻断主流程，返回 null 由下游处理。
 */
@Component
public class DefaultRequestContextManager implements RequestContextManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultRequestContextManager.class);

    private final UserProfileResolver userResolver;
    private final SessionContextResolver sessionResolver;
    private final PolicyConstraintResolver policyResolver;
    private final CagProperties cagProperties;

    public DefaultRequestContextManager(UserProfileResolver userResolver,
                                        SessionContextResolver sessionResolver,
                                        PolicyConstraintResolver policyResolver,
                                        CagProperties cagProperties) {
        this.userResolver = userResolver;
        this.sessionResolver = sessionResolver;
        this.policyResolver = policyResolver;
        this.cagProperties = cagProperties;
    }

    @Override
    public RequestContext buildContext(Long userId, String conversationId,
                                      boolean ragEnabled, int messageCount) {
        // 各维度独立收集，互不耦合
        UserContext user = resolveSafe(() -> userResolver.resolve(userId),
                UserContext.class, userId);
        SessionContext session = resolveSafe(() -> sessionResolver.resolve(conversationId, messageCount),
                SessionContext.class, conversationId);
        PolicyContext policy = resolveSafe(() -> policyResolver.resolve(user, ragEnabled),
                PolicyContext.class, ragEnabled);

        RequestContext ctx = new RequestContext(user, session, policy);

        if (cagProperties.isLogContext()) {
            log.info("CAG context assembled: userId={}, roles={}, ragEnabled={}, constraints={}",
                    userId,
                    user != null ? user.roles() : "unknown",
                    ragEnabled,
                    policy != null ? policy.constraints().size() : 0);
        }

        return ctx;
    }

    /**
     * 安全解析：任何策略失败不阻断主流程，记录警告并返回 null
     */
    private <T> T resolveSafe(Supplier<T> resolver, Class<T> type, Object hint) {
        try {
            return resolver.get();
        } catch (Exception e) {
            log.warn("CAG resolver failed for {} (hint={}): {}",
                    type.getSimpleName(), hint, e.getMessage());
            return null;
        }
    }
}
