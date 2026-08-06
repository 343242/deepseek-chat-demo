package com.smart.rag.infrastructure.messaging.redis;

import com.smart.rag.infrastructure.messaging.BackoffSchedule;
import com.smart.rag.infrastructure.messaging.MessagingMetrics;
import com.smart.rag.infrastructure.messaging.MessagingProperties;
import com.smart.rag.infrastructure.messaging.ZSetDelayQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Redis Stream 测试基座（Testcontainers redis:8）——共享业务 RedisTemplate（独立连接工厂，
 * 镜像生产 P1-4 隔离结构）、消费连接池、keys/backoff/delayQueue 等测试用组件。
 */
@Testcontainers
public abstract class AbstractRedisStreamTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8.2.6-bookworm"))
        .withExposedPorts(6379)
        .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    private static LettuceConnectionFactory businessFactory;
    private static StringRedisTemplate businessTemplate;
    private static RedisStreamConsumerConnections consumerConnections;
    private static MessagingProperties properties;
    private static RedisStreamKeys keys;
    private static RedisStreamDeadLetterWriter deadLetterWriter;
    private static ZSetDelayQueue delayQueue;
    private static BackoffSchedule backoffSchedule;

    @BeforeAll
    static void setupRedis() {
        String host = REDIS.getHost();
        int port = REDIS.getMappedPort(6379);

        businessFactory = new LettuceConnectionFactory(host, port);
        businessFactory.afterPropertiesSet();
        businessTemplate = new StringRedisTemplate(businessFactory);
        businessTemplate.afterPropertiesSet();

        RedisProperties redisProperties = new RedisProperties();
        redisProperties.setHost(host);
        redisProperties.setPort(port);
        redisProperties.setTimeout(Duration.ofSeconds(3));

        properties = new MessagingProperties("SMART_RAG_", Duration.ofSeconds(30), null, null, null, null, null);
        consumerConnections = new RedisStreamConsumerConnections(redisProperties, properties);
        keys = new RedisStreamKeys(properties);
        deadLetterWriter = new RedisStreamDeadLetterWriter(businessTemplate, properties);
        delayQueue = new ZSetDelayQueue(businessTemplate);
        backoffSchedule = new BackoffSchedule(properties.backoffMs());
    }

    @AfterAll
    static void teardownRedis() {
        if (consumerConnections != null) {
            consumerConnections.close();
        }
        if (businessFactory != null) {
            businessFactory.destroy();
        }
    }

    static StringRedisTemplate business() {
        return businessTemplate;
    }

    static RedisStreamConsumerConnections connections() {
        return consumerConnections;
    }

    /** 每测试独立消费连接实例（bus.shutdown() 会关闭其工厂；共享实例会被首个测试销毁）。 */
    static RedisStreamConsumerConnections newConnections() {
        RedisProperties redisProperties = new RedisProperties();
        redisProperties.setHost(REDIS.getHost());
        redisProperties.setPort(REDIS.getMappedPort(6379));
        redisProperties.setTimeout(Duration.ofSeconds(3));
        return new RedisStreamConsumerConnections(redisProperties, properties);
    }

    static MessagingProperties props() {
        return properties;
    }

    static RedisStreamKeys keys() {
        return keys;
    }

    static RedisStreamDeadLetterWriter dlqWriter() {
        return deadLetterWriter;
    }

    static ZSetDelayQueue delayQueue() {
        return delayQueue;
    }

    static BackoffSchedule backoff() {
        return backoffSchedule;
    }

    static MessagingMetrics metrics() {
        return new MessagingMetrics(null);
    }

    static RedisConnectionFactory businessConnectionFactory() {
        return businessFactory;
    }

    static void flushAll() {
        businessTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    /** 构造带自定义 redis 配置的 properties（启动期断言在 auto-config 层，测试直构不受限）。 */
    static MessagingProperties customProps(MessagingProperties.RedisStreamConfig config) {
        return new MessagingProperties("SMART_RAG_", Duration.ofSeconds(30), null, null, null, null, config);
    }
}
