package com.smart.rag.agent.event;

/**
 * Agent 事件优先级枚举
 * <p>
 * 与数据库 agent_session_event.priority 列（SMALLINT/INT）对应，
 * 通过 {@link AgentEventPriorityHandler} 进行 Java 枚举与数据库整数的转换。
 */
public enum AgentEventPriority {

    /** Critical -- 意图分类、中间答案、护栏触发 */
    CRITICAL(1),

    /** High -- 自省结果、检索策略变更 */
    HIGH(2),

    /** Normal -- Tool 调用记录 */
    NORMAL(3);

    private final int value;

    AgentEventPriority(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    /**
     * 根据数值获取对应的优先级枚举，未匹配时返回 {@link #NORMAL}。
     */
    public static AgentEventPriority fromValue(int value) {
        for (AgentEventPriority p : values()) {
            if (p.value == value) {
                return p;
            }
        }
        return NORMAL;
    }
}
