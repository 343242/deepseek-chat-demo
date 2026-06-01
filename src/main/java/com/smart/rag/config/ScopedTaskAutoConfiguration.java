package com.smart.rag.config;

import com.smart.rag.infrastructure.concurrent.DefaultScopedTasks;
import com.smart.rag.infrastructure.concurrent.ScopeObserver;
import com.smart.rag.infrastructure.concurrent.ScopedTaskProperties;
import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.infrastructure.concurrent.context.ContextCarrier;
import com.smart.rag.infrastructure.concurrent.executor.DefaultScopeExecutorFactory;
import com.smart.rag.infrastructure.concurrent.executor.ScopeExecutorFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

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
            ScopeObserver scopeObserver,
            List<ContextCarrier<?>> contextCarriers
    ) {
        return new DefaultScopedTasks(executorFactory, properties, scopeObserver, contextCarriers);
    }
}
