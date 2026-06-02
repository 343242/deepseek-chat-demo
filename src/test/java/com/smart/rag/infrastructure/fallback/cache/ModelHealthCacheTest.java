package com.smart.rag.infrastructure.fallback.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("ModelHealthCache")
class ModelHealthCacheTest {

    private ModelHealthCache cache;
    private RMapCache<String, HealthEntry> mapCache;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        RedissonClient redisson = mock(RedissonClient.class);
        mapCache = mock(RMapCache.class);
        doReturn(mapCache).when(redisson).getMapCache(anyString());
        cache = new ModelHealthCache(redisson, 30, 15);
    }

    @Test
    @DisplayName("putHealthy writes HEALTHY entry with correct TTL")
    void putHealthyWritesCorrectEntry() {
        cache.putHealthy("bailian/qwen-plus", 120L);

        verify(mapCache).put(eq("bailian/qwen-plus"),
                argThat(entry -> entry.status() == HealthStatus.HEALTHY
                        && entry.latencyMs() == 120L
                        && entry.modelId().equals("bailian/qwen-plus")),
                eq(30L), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("putUnhealthy writes UNHEALTHY entry with shorter TTL")
    void putUnhealthyWritesCorrectEntry() {
        cache.putUnhealthy("bailian/qwen-plus");

        verify(mapCache).put(eq("bailian/qwen-plus"),
                argThat(entry -> entry.status() == HealthStatus.UNHEALTHY
                        && entry.latencyMs() == -1L),
                eq(15L), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("get delegates to RMapCache")
    void getDelegatesToMapCache() {
        HealthEntry entry = new HealthEntry("model-a", HealthStatus.HEALTHY,
                System.currentTimeMillis(), 100L);
        when(mapCache.get("model-a")).thenReturn(entry);

        HealthEntry result = cache.get("model-a");
        assertThat(result).isSameAs(entry);
    }

    @Test
    @DisplayName("get returns null for missing key")
    void getReturnsNullForMissingKey() {
        when(mapCache.get("model-a")).thenReturn(null);
        assertThat(cache.get("model-a")).isNull();
    }
}
