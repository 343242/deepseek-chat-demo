package com.demo.deepseekchat;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = {
        org.springframework.ai.model.zhipuai.autoconfigure.ZhiPuAiChatAutoConfiguration.class,
        org.springframework.ai.model.minimax.autoconfigure.MiniMaxChatAutoConfiguration.class
})
@MapperScan("com.demo.deepseekchat.**.mapper")
public class DeepseekChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeepseekChatApplication.class, args);
    }
}
