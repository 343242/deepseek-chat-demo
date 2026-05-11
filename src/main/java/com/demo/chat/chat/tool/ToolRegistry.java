package com.demo.chat.chat.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 工具注册中心
 * <p>
 * 自动发现 Spring 容器中所有标注了 {@code @Tool} 方法的 Bean，
 * 通过 {@link ToolCallbacks#from(Object...)} 转换为 {@link ToolCallback} 数组，
 * 供 {@link org.springframework.ai.chat.client.advisor.ToolCallAdvisor} 使用。
 * <p>
 * 新增工具只需创建 {@code @Component} 类并标注 {@code @Tool} 方法，
 * 零修改本类和任何现有代码（OCP）。
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final ToolCallback[] callbacks;

    /**
     * 在构造时立即收集所有工具 Bean。
     * <p>
     * 使用 ObjectProvider 而非 List 注入，避免无工具 Bean 时启动失败。
     * 收集时机：构造器执行时（Spring 容器初始化阶段），非延迟。
     *
     * @param toolBeans 所有包含 @Tool 方法的 Spring Bean
     */
    public ToolRegistry(ObjectProvider<List<Object>> toolBeans) {
        List<Object> beans = toolBeans.getIfAvailable(ArrayList::new);
        if (beans.isEmpty()) {
            this.callbacks = new ToolCallback[0];
            log.info("No tool beans found, tool calling disabled");
        } else {
            List<ToolCallback> all = new ArrayList<>();
            for (Object bean : beans) {
                ToolCallback[] fromBean = ToolCallbacks.from(bean);
                all.addAll(Arrays.asList(fromBean));
                log.debug("Discovered {} tools from {}", fromBean.length,
                        bean.getClass().getSimpleName());
            }
            this.callbacks = all.toArray(ToolCallback[]::new);
            log.info("Registered {} tool callbacks", callbacks.length);
        }
    }

    /**
     * 获取所有已注册的 ToolCallback
     *
     * @return 不可变数组副本，可能为空
     */
    public ToolCallback[] getToolCallbacks() {
        return callbacks;
    }

    /**
     * 是否有可用工具
     */
    public boolean hasTools() {
        return callbacks.length > 0;
    }

    /**
     * 空注册表 — 供 ObjectProvider#getIfAvailable 的 fallback
     */
    public static ToolRegistry empty() {
        return new ToolRegistry();
    }

    /**
     * 内部无参构造，仅用于 empty() 工厂方法
     */
    private ToolRegistry() {
        this.callbacks = new ToolCallback[0];
    }

    /**
     * 已注册工具数量
     */
    public int size() {
        return callbacks.length;
    }
}
