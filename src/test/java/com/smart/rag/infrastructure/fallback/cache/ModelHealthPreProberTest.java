package com.smart.rag.infrastructure.fallback.cache;

import com.smart.rag.infrastructure.fallback.ChatCandidatesProperties;
import com.smart.rag.infrastructure.fallback.ModelCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("ModelHealthPreProber")
class ModelHealthPreProberTest {

    private static ModelCandidate candidate(String provider, String model, int priority, boolean enabled) {
        return new ModelCandidate(provider + "-" + model, provider, model, priority, enabled, false);
    }

    private ChatCandidatesProperties testProps(List<ModelCandidate> candidates) {
        return new ChatCandidatesProperties(candidates, true, 10, true, 30, 20_000L);
    }

    @Test
    @DisplayName("probes candidates without HEALTHY cache")
    void probesCandidatesWithoutCache() {
        var props = testProps(List.of(
                candidate("bailian", "qwen-plus", 1, true),
                candidate("zhipu", "glm-4", 2, true)
        ));
        var healthCache = mock(ModelHealthCache.class);
        when(healthCache.get(anyString())).thenReturn(null);

        var probed = new AtomicInteger(0);
        ModelHealthPreProber.ProbeFunction probeFn = modelId -> {
            probed.incrementAndGet();
            return 100L;
        };

        var preProber = new ModelHealthPreProber(props, healthCache, probeFn);
        preProber.preProbe();

        assertThat(probed.get()).isEqualTo(2);
        verify(healthCache).putHealthy("bailian/qwen-plus", 100L);
        verify(healthCache).putHealthy("zhipu/glm-4", 100L);
    }

    @Test
    @DisplayName("skips candidates with fresh HEALTHY cache")
    void skipsFreshHealthyCandidates() {
        var props = testProps(List.of(
                candidate("bailian", "qwen-plus", 1, true)
        ));
        var healthCache = mock(ModelHealthCache.class);
        when(healthCache.get("bailian/qwen-plus")).thenReturn(
                new HealthEntry("bailian/qwen-plus", HealthStatus.HEALTHY,
                        Instant.now().toEpochMilli(), 80L));

        var probed = new AtomicInteger(0);
        ModelHealthPreProber.ProbeFunction probeFn = modelId -> {
            probed.incrementAndGet();
            return 90L;
        };

        var preProber = new ModelHealthPreProber(props, healthCache, probeFn);
        preProber.preProbe();

        assertThat(probed.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("disabled candidates are skipped")
    void skipsDisabledCandidates() {
        var props = testProps(List.of(
                candidate("bailian", "qwen-plus", 1, false),
                candidate("zhipu", "glm-4", 2, true)
        ));
        var healthCache = mock(ModelHealthCache.class);
        when(healthCache.get(anyString())).thenReturn(null);

        var probed = new AtomicInteger(0);
        ModelHealthPreProber.ProbeFunction probeFn = modelId -> {
            probed.incrementAndGet();
            return 50L;
        };

        var preProber = new ModelHealthPreProber(props, healthCache, probeFn);
        preProber.preProbe();

        assertThat(probed.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("marks UNHEALTHY when probe returns -1")
    void marksUnhealthyOnProbeFailure() {
        var props = testProps(List.of(
                candidate("bailian", "qwen-plus", 1, true)
        ));
        var healthCache = mock(ModelHealthCache.class);
        when(healthCache.get(anyString())).thenReturn(null);

        ModelHealthPreProber.ProbeFunction probeFn = modelId -> -1L;

        var preProber = new ModelHealthPreProber(props, healthCache, probeFn);
        preProber.preProbe();

        verify(healthCache).putUnhealthy("bailian/qwen-plus");
        verify(healthCache, never()).putHealthy(anyString(), anyLong());
    }

    @Test
    @DisplayName("no-op when candidate list is empty")
    void noOpWhenEmptyList() {
        var props = testProps(List.of());
        var healthCache = mock(ModelHealthCache.class);

        ModelHealthPreProber.ProbeFunction probeFn = modelId -> 0L;

        var preProber = new ModelHealthPreProber(props, healthCache, probeFn);
        preProber.preProbe();

        verifyNoInteractions(healthCache);
    }
}
