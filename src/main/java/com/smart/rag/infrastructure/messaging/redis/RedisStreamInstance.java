package com.smart.rag.infrastructure.messaging.redis;

import com.smart.rag.infrastructure.messaging.MessagingProperties;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * 实例标识与 consumer 名（design §1）：consumer name = {@code {consumer-name-prefix}{instanceId}}，
 * instanceId = hostname（失败回退 UUID 短码）。PEL 归属追踪用——XAUTOCLAIM / XINFO 可定位消息
 * 卡在哪个实例。
 */
final class RedisStreamInstance {

    private static final String INSTANCE_ID = computeInstanceId();

    private RedisStreamInstance() {}

    private static String computeInstanceId() {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            if (hostname != null && !hostname.isBlank()) {
                return hostname;
            }
        } catch (UnknownHostException e) {
            // fall through to random short code
        }
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** 本实例的 consumer 名（前缀可配，默认 {@code app:}）。 */
    static String consumerName(MessagingProperties properties) {
        return properties.redis().consumerNamePrefix() + INSTANCE_ID;
    }
}
