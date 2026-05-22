package com.smart.rag.chat.mode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 对话模式路由器
 * <p>
 * 根据请求中的 mode 字段路由到对应的 ChatModeStrategy。
 * 通过 Spring 构造器注入自动发现所有 ChatModeStrategy 实现（OCP）。
 * <p>
 * 新增模式只需新增 ChatModeStrategy 实现类，无需修改本类。
 */
public class ModeRouter {

    private static final Logger log = LoggerFactory.getLogger(ModeRouter.class);

    private final Map<ChatMode, ChatModeStrategy> strategyMap;

    public ModeRouter(List<ChatModeStrategy> strategies) {
        this.strategyMap = new EnumMap<>(ChatMode.class);
        for (ChatModeStrategy strategy : strategies) {
            strategyMap.put(strategy.getMode(), strategy);
            log.info("Registered chat mode strategy: {}", strategy.getMode());
        }

        // 确保默认策略存在
        if (!strategyMap.containsKey(ChatMode.SIMPLE)) {
            strategyMap.put(ChatMode.SIMPLE, new SimpleModeStrategy());
            log.info("Fallback: registered default SimpleModeStrategy");
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
