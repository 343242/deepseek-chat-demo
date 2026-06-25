package com.smart.rag.infrastructure.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 登录/注册限流配置（per-IP 滑动窗口，Redis Lua 原子递增）。
 * <p>
 * 登录与注册使用 <b>独立 key</b>（{@code ratelimit:login:*} / {@code ratelimit:register:*}）与
 * <b>独立阈值</b>，互不消耗配额——避免压测或正常使用时两类请求互相挤占。
 * 默认值偏宽松以适配开发/压测；生产可经 {@code application-*.yml} 的 {@code app.ratelimit.*} 覆盖。
 */
@ConfigurationProperties(prefix = "app.ratelimit")
public record RateLimitProperties(
        Window login,
        Window register
) {
    /** 单个限流窗口：{@code limit} 次数 / {@code ttlSec} 秒。 */
    public record Window(int limit, int ttlSec) {
        /** 登录默认 30 次 / 30 分钟 */
        public static final Window LOGIN_DEFAULT = new Window(30, 1800);
        /** 注册默认 10 次 / 20 分钟（注册是更大攻击面，阈值更紧） */
        public static final Window REGISTER_DEFAULT = new Window(10, 1200);
    }

    /** 防御性兜底：配置缺失时回落到默认窗口，避免启动失败或 NPE。 */
    public Window login() { return login != null ? login : Window.LOGIN_DEFAULT; }
    public Window register() { return register != null ? register : Window.REGISTER_DEFAULT; }
}
