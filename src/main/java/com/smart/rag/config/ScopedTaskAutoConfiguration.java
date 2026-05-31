package com.smart.rag.config;

import com.smart.rag.common.concurrent.DefaultScopedTasks;
import com.smart.rag.common.concurrent.ScopeObserver;
import com.smart.rag.common.concurrent.ScopedTaskProperties;
import com.smart.rag.common.concurrent.ScopedTasks;
import com.smart.rag.common.concurrent.executor.DefaultScopeExecutorFactory;
import com.smart.rag.common.concurrent.executor.ScopeExecutorFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ScopedTaskProperties.class)
public class ScopedTaskAutoConfiguration {

    @Bean(destroyMethod = "close")
    public ScopeExecutorFactory scopeExecutorFactory(ScopedTaskProperties properties) {
        return new DefaultScopeExecutorFactory(properties);
    }

    @Bean
    public ScopeObserver scopeObserver() {
        return ScopeObserver.NOOP;
    }

    @Bean
    public ScopedTasks scopedTasks(
            ScopeExecutorFactory executorFactory,
            ScopedTaskProperties properties,
            ScopeObserver scopeObserver
    ) {
        return new DefaultScopedTasks(executorFactory, properties, scopeObserver);
    }
}
