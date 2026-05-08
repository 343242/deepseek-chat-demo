package com.demo.chat;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = {
        org.springframework.ai.model.zhipuai.autoconfigure.ZhiPuAiChatAutoConfiguration.class,
        org.springframework.ai.model.minimax.autoconfigure.MiniMaxChatAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class
})
@MapperScan("com.demo.chat.**.mapper")
public class ChatDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatDemoApplication.class, args);
    }
}
