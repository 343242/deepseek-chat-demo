package com.smart.rag.infrastructure.llm.strategy.provider;

import java.net.URI;

/**
 * 百炼 provider.url → SDK / 手写客户端 URL 归一化工具。
 * <p>
 * 双 profile 现状：dev 的 provider.url 为 workspace 域名（无路径）；stable 为共享域名
 * 兼容层前缀（{@code https://dashscope.aliyuncs.com/compatible-mode/v1}）。SDK 原生路由与
 * rerank 兼容端点均以「域名 + 各自路径」访问，provider.url 携带的兼容层路径必须剥离。
 */
final class DashScopeUrls {

    private DashScopeUrls() {}

    /** scheme://host[:port]（剥离 provider.url 上的任何路径） */
    static String domainBase(String providerUrl) {
        URI uri = URI.create(providerUrl.trim());
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("Invalid bailian provider url: " + providerUrl);
        }
        StringBuilder sb = new StringBuilder(uri.getScheme()).append("://").append(uri.getHost());
        if (uri.getPort() != -1) {
            sb.append(':').append(uri.getPort());
        }
        return sb.toString();
    }

    /** SDK baseUrl：域名 + {@code /api/v1} 前缀（SDK 自动拼接 /services/... 路径） */
    static String sdkBaseUrl(String providerUrl) {
        return domainBase(providerUrl) + "/api/v1";
    }
}
