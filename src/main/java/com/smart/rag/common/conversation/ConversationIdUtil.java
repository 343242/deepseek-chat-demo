package com.smart.rag.common.conversation;

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
     * <p>
     * 安全性说明：此前缀用于 MyBatis LIKE 查询（TokenUsageMapper.xml）中的用户隔离。
     * 因为 userId 是 Long 类型，拼接后的前缀不可能包含 LIKE 通配符 '%'，
     * 所以前缀本身不会被 SQL 注入利用。此断言作为额外防御层：
     * 如果未来 conversationId 格式变更导致前缀意外包含 '%'，会立即失败。
     *
     * @param userId 用户 ID
     * @return LIKE 前缀，格式: u_{userId}_%
     */
    public static String buildLikePrefix(Long userId) {
        String prefixPart = PREFIX + userId + SEPARATOR;
        assertNoLikeWildcards(prefixPart);
        return prefixPart + "%";
    }

    /**
     * 验证字符串中不包含 SQL LIKE 通配符 '%'（仅供测试调用）。
     * <p>
     * 用于 buildLikePrefix 的防御性断言。仅检查 '%' 通配符，不检查 '_'，
     * 因为 '_' 是 conversationId 格式 "u_{userId}_{rawId}" 的合法分隔符，
     * 出现在固定位置不会构成安全风险。
     *
     * @param value 待验证的字符串
     * @throws IllegalStateException 如果包含 %
     */
    public static void assertNoLikeWildcards(String value) {
        if (value.contains("%")) {
            throw new IllegalStateException(
                "ConversationId LIKE prefix must not contain '%' wildcard, got: " + value);
        }
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
