package com.demo.chat;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Chat Demo 主启动类
 * <p>
 * scanBasePackages 精确到业务包，避免扫描无用的第三方依赖。
 * MapperScan 精确列出 Mapper 所在包，避免通配符扫描。
 */
@SpringBootApplication(
        scanBasePackages = "com.demo.chat",
        exclude = {
                org.springframework.ai.model.zhipuai.autoconfigure.ZhiPuAiChatAutoConfiguration.class,
                org.springframework.ai.model.minimax.autoconfigure.MiniMaxChatAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class,
                org.springframework.ai.model.chat.memory.repository.jdbc.autoconfigure.JdbcChatMemoryRepositoryAutoConfiguration.class
        }
)
@ConfigurationPropertiesScan("com.demo.chat")
@MapperScan({
        "com.demo.chat.user.mapper",
        "com.demo.chat.chat.mapper",
        "com.demo.chat.conversation.mapper",
        "com.demo.chat.rag.mapper",
        "com.demo.chat.team.mapper"
})
public class ChatDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatDemoApplication.class, args);
    }
}
