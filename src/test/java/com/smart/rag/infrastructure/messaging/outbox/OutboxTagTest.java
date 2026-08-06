package com.smart.rag.infrastructure.messaging.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * tag 持久化 round-trip（评审 P1-8）：{@code MessageEnvelope.tag} 经 outbox INSERT + relay
 * 重建后保留（null 与非 null 均保留）——当前三 publisher 不用 tag，但传输元数据不应丢。
 */
class OutboxTagTest extends AbstractRelayRoundTripTest {

    @Test
    @DisplayName("非 null tag 经 round-trip 保留")
    void nonNullTagSurvives() {
        insertFullRow(String.class.getName(), "\"doc\"", "high-priority", "h", null, Map.of());

        drainAndCapture();

        assertThat(captured.get().tag()).isEqualTo("high-priority");
    }

    @Test
    @DisplayName("null tag 保持 null（不误填空串）")
    void nullTagStaysNull() {
        insertFullRow(String.class.getName(), "\"doc\"", null, "h", null, Map.of());

        drainAndCapture();

        assertThat(captured.get().tag()).isNull();
    }
}
