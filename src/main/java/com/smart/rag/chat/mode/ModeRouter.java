package com.smart.rag.chat.mode;

import com.smart.rag.mode.ChatMode;
import com.smart.rag.mode.ChatModeStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对话模式路由器
 * <p>
 * 根据请求中的 mode 字段路由到对应的 ChatModeStrategy。
 * 通过 Spring 构造器注入自动发现所有 ChatModeStrategy 实现（OCP）。
 * <p>
 * 新增模式只需新增 ChatModeStrategy 实现类，无需修改本类。
 * <p>
 * 构造时 fail-fast 校验：所有 ChatMode 枚举值必须有对应策略注册，且无重复。
 */
@Component
public class ModeRouter {

    private static final Logger log = LoggerFactory.getLogger(ModeRouter.class);

    private final Map<ChatMode, ChatModeStrategy> strategyMap;

    public ModeRouter(List<ChatModeStrategy> strategies) {
        this.strategyMap = new EnumMap<>(ChatMode.class);
        Set<ChatMode> seen = EnumSet.noneOf(ChatMode.class);
        for (ChatModeStrategy s : strategies) {
            ChatMode mode = s.getMode();
            if (!seen.add(mode)) {
                throw new IllegalStateException(
                    "Duplicate ChatModeStrategy for mode: " + mode);
            }
            strategyMap.put(mode, s);
            log.info("Registered chat mode strategy: {}", mode);
        }

        // fail-fast: 所有 ChatMode 必须有对应策略注册
        for (ChatMode required : ChatMode.values()) {
            if (!strategyMap.containsKey(required)) {
                throw new IllegalStateException(
                    "No ChatModeStrategy registered for mode: " + required
                    + ". Required modes: " + Arrays.toString(ChatMode.values()));
            }
        }
    }

    /**
     * 根据模式名称路由到对应策略
     *
     * @param mode 模式字符串（如 "SIMPLE", "MULTI_TURN"），null 或空返回默认策略
     * @return 对应的 ChatModeStrategy，未匹配时返回 SIMPLE 策略
     */
    public ChatModeStrategy route(String mode) {
        ChatMode chatMode = ChatMode.fromString(mode);
        ChatModeStrategy strategy = strategyMap.get(chatMode);
        if (strategy == null) {
            log.warn("No strategy found for mode={}, falling back to SIMPLE", mode);
            return strategyMap.get(ChatMode.SIMPLE);
        }
        return strategy;
    }
}
