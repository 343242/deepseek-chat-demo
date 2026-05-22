package com.smart.rag.conversation.util;

/**
 * 对话 ID 工具类
 * <p>
 * 统一管理用户隔离 conversationId 的构建和解析，
 * 避免在 ChatService、ConversationService、UsageController 中重复拼接逻辑。
 * <p>
 * 格式: u_{userId}_{rawConversationId}
 */
public final class ConversationIdUtil {

    private static final String PREFIX = "u_";
    private static final String SEPARATOR = "_";

    private ConversationIdUtil() {}

    /**
     * 构建用户隔离的 conversationId
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
     *
     * @param userId 用户 ID
     * @return LIKE 前缀，格式: u_{userId}_%
     */
    public static String buildLikePrefix(Long userId) {
        return PREFIX + userId + SEPARATOR + "%";
    }

    /**
     * 从隔离的 conversationId 中提取 userId
     *
     * @param isolatedId 隔离后的 conversationId
     * @return userId，解析失败返回 null
     */
    public static Long extractUserId(String isolatedId) {
        if (isolatedId == null || !isolatedId.startsWith(PREFIX)) {
            return null;
        }
        try {
            String afterPrefix = isolatedId.substring(PREFIX.length());
            int sepIdx = afterPrefix.indexOf(SEPARATOR);
            if (sepIdx <= 0) {
                return null;
            }
            return Long.parseLong(afterPrefix.substring(0, sepIdx));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 从隔离的 conversationId 中提取原始对话 ID
     *
     * @param isolatedId 隔离后的 conversationId
     * @return rawConversationId，解析失败返回 null
     */
    public static String extractRawId(String isolatedId) {
        if (isolatedId == null || !isolatedId.startsWith(PREFIX)) {
            return null;
        }
        String afterPrefix = isolatedId.substring(PREFIX.length());
        int sepIdx = afterPrefix.indexOf(SEPARATOR);
        if (sepIdx < 0) {
            return null;
        }
        return afterPrefix.substring(sepIdx + SEPARATOR.length());
    }
}
