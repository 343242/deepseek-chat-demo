package com.smart.rag.infrastructure.messaging.redis;

import com.smart.rag.infrastructure.messaging.MessagingProperties;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 消费连接隔离（P1-4，强制）：XREADGROUP BLOCK 长阻塞命令走独立 {@link LettuceConnectionFactory}
 * （share-native-connection=false + 独立连接池），不得占用业务共享 Redis 连接——
 * 否则单个 BLOCK 会把整个 app 的所有 RedisTemplate 操作（缓存、IdempotentHandler SETNX、业务读写）
 * 拖到 read-block-ms 级延迟（design §3 P1-4）。
 * <p>
 * <b>非 {@code RedisConnectionFactory} bean</b>：若直接声明 LettuceConnectionFactory bean，
 * Spring Boot {@code RedisAutoConfiguration} 的 {@code @ConditionalOnMissingBean(RedisConnectionFactory.class)}
 * 会整体退避，业务 {@code RedisTemplate} 将绑定到本消费连接上——隔离失效。因此本类以普通对象包装
 * 独立 factory，由 {@link RedisStreamMessageBus} 在 bean destroy 时统一关闭。
 */
public class RedisStreamConsumerConnections implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamConsumerConnections.class);

    private final LettuceConnectionFactory factory;
    private final StringRedisTemplate template;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public RedisStreamConsumerConnections(RedisProperties redisProperties, MessagingProperties properties) {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(
            redisProperties.getHost(), redisProperties.getPort());
        if (redisProperties.getPassword() != null && !redisProperties.getPassword().isEmpty()) {
            standalone.setPassword(RedisPassword.of(redisProperties.getPassword()));
        }
        if (redisProperties.getUsername() != null && !redisProperties.getUsername().isEmpty()) {
            standalone.setUsername(redisProperties.getUsername());
        }

        MessagingProperties.ConsumerConnectionConfig conn = properties.redis().consumer();
        GenericObjectPoolConfig<io.lettuce.core.api.StatefulConnection<?, ?>> poolConfig =
            new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(conn.poolMaxActive());
        poolConfig.setMaxIdle(conn.poolMaxIdle());

        LettuceClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
            .poolConfig(poolConfig)
            .commandTimeout(redisProperties.getTimeout())
            .build();

        this.factory = new LettuceConnectionFactory(standalone, clientConfig);
        this.factory.setShareNativeConnection(conn.shareNativeConnection());
        this.factory.afterPropertiesSet();

        this.template = new StringRedisTemplate(factory);
        this.template.afterPropertiesSet();
        log.info("Redis Stream consumer connection pool ready: poolMaxActive={}, shareNativeConnection={}",
            conn.poolMaxActive(), conn.shareNativeConnection());
    }

    /** 消费侧专用 StringRedisTemplate（XREADGROUP/XACK/XAUTOCLAIM/XINFO/XTRIM）。 */
    public StringRedisTemplate template() {
        return template;
    }

    public StreamOperations<String, Object, Object> streamOps() {
        return template.opsForStream();
    }

    /** 原生命令执行（String 参数版本，内部转 byte[]）。仅适用于 status/simple 回复的命令（如 XGROUP CREATE）；multi-bulk 回复走 Lua 脚本。 */
    public Object executeCommand(String command, String... args) {
        return template.execute((org.springframework.data.redis.core.RedisCallback<Object>) conn ->
            conn.execute(command, bytes(args)));
    }

    private byte[][] bytes(String... args) {
        byte[][] raw = new byte[args.length][];
        for (int i = 0; i < args.length; i++) {
            raw[i] = args[i].getBytes(StandardCharsets.UTF_8);
        }
        return raw;
    }

    byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            factory.destroy();
        } catch (Exception e) {
            log.warn("Error destroying consumer LettuceConnectionFactory", e);
        }
    }
}
