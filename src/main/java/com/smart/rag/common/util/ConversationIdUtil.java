package com.smart.rag.common.util;

/**
 * 对话 ID 工具类
 * <p>
 * 统一管理用户隔离 conversationId 的构建和解析，
 * 避免在 ChatService、ConversationService 中重复拼接逻辑。
 * <p>
 * 格式: u_{userId}_{rawConversationId}
 * <p>
 * 用量统计自 V28 起按显式 user_id 列隔离（usage_event），不再依赖此前缀做 LIKE 过滤。
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

    /**
     * 对 conversationId 进行脱敏处理，隐藏 userId 部分。
     * <p>
     * 格式 {@code u_{userId}_{rawId}} 会被转换为 {@code u_***_{rawId 的后4位}}。
     * 非 u_ 前缀的 ID 保持前2位和后4位。
     *
     * @param conversationId 原始 conversationId
     * @return 脱敏后的 ID，输入为 null 时返回 "null"
     */
    public static String mask(String conversationId) {
        if (conversationId == null) {
            return "null";
        }
        if (conversationId.startsWith(PREFIX)) {
            String rawId = extractRawId(conversationId);
            if (rawId == null) {
                return conversationId;
            }
            String suffix = rawId.length() > 4 ? rawId.substring(rawId.length() - 4) : rawId;
            return PREFIX + "***" + SEPARATOR + suffix;
        }
        if (conversationId.length() <= 6) {
            return conversationId;
        }
        return conversationId.substring(0, 2) + "***" + conversationId.substring(conversationId.length() - 4);
    }
}
