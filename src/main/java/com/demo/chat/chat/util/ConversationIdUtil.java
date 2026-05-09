package com.demo.chat.chat.util;

/**
 * 对话 ID 工具类
 * <p>
 * 统一管理用户隔离 conversationId 的构建和解析，
 * 避免在 ChatService、ConversationService、UsageController 中重复拼接逻辑。
 */
public final class ConversationIdUtil {

    private static final String PREFIX = "u_";
    private static final String SEPARATOR = "_";

    private ConversationIdUtil() {}

    /**
     * 构建用户隔离的 conversationId
     * <p>
     * 格式: u_{userId}_{rawConversationId}
     *
     * @param userId             用户 ID
     * @param rawConversationId  原始对话 ID
     * @return 隔离后的 conversationId
     */
    public static String buildIsolatedId(Long userId, String rawConversationId) {
        return PREFIX + userId + SEPARATOR + rawConversationId;
    }

    /**
     * 构建用户隔离的 LIKE 前缀
     * <p>
     * 格式: u_{userId}_%
     *
     * @param userId 用户 ID
     * @return LIKE 前缀
     */
    public static String buildLikePrefix(Long userId) {
        return PREFIX + userId + SEPARATOR + "%";
    }

    /**
     * 从存储的 conversationId 中剥离用户前缀
     * <p>
     * "u_123_default" → "default"
     *
     * @param isolatedConversationId 隔离后的 conversationId
     * @return 原始 conversationId
     */
    public static String stripUserPrefix(String isolatedConversationId) {
        if (isolatedConversationId == null) return null;
        int firstUnderscore = isolatedConversationId.indexOf(SEPARATOR);
        if (firstUnderscore < 0) return isolatedConversationId;
        int secondUnderscore = isolatedConversationId.indexOf(SEPARATOR, firstUnderscore + 1);
        if (secondUnderscore < 0) return isolatedConversationId;
        return isolatedConversationId.substring(secondUnderscore + 1);
    }
}
