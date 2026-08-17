package com.smart.rag.infrastructure.llm.usage;

/**
 * 用量事件出口 — 基础设施层端口，由用量模块（{@code com.smart.rag.usage.UsageRecorder}）实现。
 * <p>
 * 依赖方向：{@code infrastructure.llm.adapter} 的采集装饰器只依赖本接口（DIP），
 * 不感知消息总线/落库细节。实现必须自行吞掉所有异常——用量采集是非关键路径，
 * 绝不允许向模型调用主链路抛出。
 */
@FunctionalInterface
public interface UsageEventSink {

    /**
     * 接收一次模型调用的用量采样。实现必须保证不抛出。
     */
    void accept(UsageSample sample);
}
