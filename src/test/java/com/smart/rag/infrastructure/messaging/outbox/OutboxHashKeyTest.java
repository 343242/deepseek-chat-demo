package com.smart.rag.infrastructure.messaging.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * hash_key 持久化 round-trip（P1-4 配套）：{@code rag_index_document} 的有序分区键
 * （= documentId）经 outbox INSERT + relay 重建后保留——child 1 的 RedisStreamMessageBus
 * 依赖 envelope.hashKey 写入 stream 记录，丢失会导致分区语义退化。
 */
class OutboxHashKeyTest extends AbstractRelayRoundTripTest {

    @Test
    @DisplayName("relay 重建 envelope 恢复 hashKey（rag_index_document 有序分区键）")
    void hashKeySurvivesRoundTrip() {
        insertFullRow(String.class.getName(), "\"doc\"", null, "doc-987", "dedup-x",
            Map.of("traceparent", "00-a-b-01"));

        drainAndCapture();

        assertThat(captured.get().hashKey()).isEqualTo("doc-987");
        assertThat(captured.get().deduplicationKey()).isEqualTo("dedup-x");
        assertThat(captured.get().topic()).isEqualTo("rag_index_document");
    }

    @Test
    @DisplayName("hashKey 为 null 时保持 null（不误填）")
    void nullHashKeyStaysNull() {
        insertFullRow(String.class.getName(), "\"doc\"", null, null, null, Map.of());

        drainAndCapture();

        assertThat(captured.get().hashKey()).isNull();
    }
}
