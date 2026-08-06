package com.smart.rag;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Smart RAG 主启动类
 * <p>
 * scanBasePackages 精确到业务包，避免扫描无用的第三方依赖。
 * MapperScan 精确列出 Mapper 所在包，避免通配符扫描。
 */
@EnableAsync
@SpringBootApplication(
        scanBasePackages = "com.smart.rag",
        exclude = {
                org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class
        }
)
@ConfigurationPropertiesScan("com.smart.rag")
@MapperScan(
        annotationClass = org.apache.ibatis.annotations.Mapper.class,
        basePackages = {
        "com.smart.rag.user.mapper",
        "com.smart.rag.chat.mapper",
        "com.smart.rag.conversation.mapper",
        "com.smart.rag.rag.mapper",
        "com.smart.rag.agent.event",
        "com.smart.rag.team.mapper",
        "com.smart.rag.modelconfig.mapper",
        "com.smart.rag.mcp.admin.mapper",
        "com.smart.rag.infrastructure.audit.mapper",
        "com.smart.rag.infrastructure.trace",
        "com.smart.rag.infrastructure.messaging.outbox"
})
public class SmartRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartRagApplication.class, args);
    }
}
