package com.smart.rag.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 拦截器装配。
 * <p>
 * <b>拦截器顺序</b>（重要）：{@link OptimisticLockerInnerInterceptor} 必须在
 * {@link PaginationInnerInterceptor} <b>之前</b>注册——分页拦截器会改写 SQL 加 LIMIT，
 * 若乐观锁在分页之后跑，乐观锁的 {@code WHERE version = ?} 条件会被分页 SQL 干扰。
 * <p>
 * <b>v4 B3</b>：注册 {@link OptimisticLockerInnerInterceptor} 启用 {@code @Version} 注解支持。
 * 此前仅有分页拦截器，导致任何使用 {@code @Version} 的实体（如本期新增的
 * {@code McpServerConfig} / {@code McpToolConfig}）的乐观锁字段失效。
 */
@Configuration
public class MyBatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }
}
