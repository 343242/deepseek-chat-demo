package com.smart.rag.config;

import com.smart.rag.common.concurrent.DefaultScopedTasks;
import com.smart.rag.common.concurrent.ScopedTasks;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ScopedTaskAutoConfiguration {

    @Bean
    public ScopedTasks scopedTasks() {
        return new DefaultScopedTasks();
    }
}
