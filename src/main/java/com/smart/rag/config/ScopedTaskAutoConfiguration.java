package com.smart.rag.config;

import com.smart.rag.common.concurrent.DefaultScopedTasks;
import com.smart.rag.common.concurrent.ScopeObserver;
import com.smart.rag.common.concurrent.ScopedTaskProperties;
import com.smart.rag.common.concurrent.ScopedTasks;
import com.smart.rag.common.concurrent.executor.DefaultScopeExecutorFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ScopedTaskProperties.class)
public class ScopedTaskAutoConfiguration {

    @Bean
    public DefaultScopeExecutorFactory scopeExecutorFactory(ScopedTaskProperties properties) {
        return new DefaultScopeExecutorFactory(properties);
    }

    @Bean
    public ScopeObserver scopeObserver() {
        return ScopeObserver.NOOP;
    }

    @Bean
    public ScopedTasks scopedTasks(
            DefaultScopeExecutorFactory executorFactory,
            ScopedTaskProperties properties,
            ScopeObserver scopeObserver
    ) {
        return new DefaultScopedTasks(executorFactory, properties, scopeObserver);
    }
}
