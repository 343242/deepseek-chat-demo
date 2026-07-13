package com.smart.rag.mcp.core;

/**
 * MCP server 健康状态（领域模型）——三态熔断器状态的<b>只读投影</b>，非独立状态机（§11.2 / design D-7）。
 * <ul>
 *   <li>{@link Status#ALIVE} ← 熔断器 CLOSED（正常放行）</li>
 *   <li>{@link Status#DEGRADED} ← 熔断器 HALF_OPEN（受控探测）</li>
 *   <li>{@link Status#DOWN} ← 熔断器 OPEN（快速失败）；或占位 server（client=null，未连接）</li>
 * </ul>
 * 状态机本身（计数/转换/恢复）由 {@code infrastructure.fallback} 三态熔断器承担，本类型只读投影。
 */
public record McpServerHealth(Status status, String detail) {

    public enum Status { ALIVE, DEGRADED, DOWN }

    public static McpServerHealth alive() {
        return new McpServerHealth(Status.ALIVE, null);
    }

    public static McpServerHealth degraded(String detail) {
        return new McpServerHealth(Status.DEGRADED, detail);
    }

    public static McpServerHealth down(String detail) {
        return new McpServerHealth(Status.DOWN, detail);
    }
}
