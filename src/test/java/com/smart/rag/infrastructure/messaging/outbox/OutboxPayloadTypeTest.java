package com.smart.rag.infrastructure.messaging.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * payload_type 反序列化 round-trip（评审 P1-4）：chat/usage/etl 三类 payload 经 outbox
 * 往返后按 {@code Class.forName(payload_type)} + {@code codec.decode} 正确还原——
 * relay 无需维护 topic→Class 注册表（新增 topic 只需 payload 类在 classpath）。
 */
class OutboxPayloadTypeTest extends AbstractRelayRoundTripTest {

    @Test
    @DisplayName("usage payload：JSON 按 payload_type 还原为 UsagePayload 实例")
    void usagePayloadRoundTrips() {
        insertFullRow(com.smart.rag.chat.service.UsagePayload.class.getName(),
            "{\"conversationId\":\"c1\",\"candidateId\":\"m1\",\"promptTokens\":5,"
                + "\"completionTokens\":6,\"totalTokens\":11,\"durationMs\":7}",
            null, null, null, Map.of());

        drainAndCapture();

        assertThat(captured.get().payload())
            .isInstanceOf(com.smart.rag.chat.service.UsagePayload.class);
        com.smart.rag.chat.service.UsagePayload p =
            (com.smart.rag.chat.service.UsagePayload) captured.get().payload();
        assertThat(p.conversationId()).isEqualTo("c1");
        assertThat(p.totalTokens()).isEqualTo(11L);
    }

    @Test
    @DisplayName("etl payload：还原为 EtlCandidate 实例")
    void etlPayloadRoundTrips() {
        insertFullRow(com.smart.rag.rag.etl.EtlCandidate.class.getName(),
            "{\"documentId\":9,\"bucket\":\"b\",\"objectKey\":\"k\",\"fileName\":\"f.pdf\","
                + "\"mimeType\":\"application/pdf\",\"fileSize\":100,\"userId\":1,\"teamId\":null}",
            null, null, null, Map.of());

        drainAndCapture();

        assertThat(captured.get().payload()).isInstanceOf(com.smart.rag.rag.etl.EtlCandidate.class);
        com.smart.rag.rag.etl.EtlCandidate c =
            (com.smart.rag.rag.etl.EtlCandidate) captured.get().payload();
        assertThat(c.documentId()).isEqualTo(9L);
        assertThat(c.fileName()).isEqualTo("f.pdf");
    }
}
