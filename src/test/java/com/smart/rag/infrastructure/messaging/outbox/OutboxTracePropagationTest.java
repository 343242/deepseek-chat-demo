package com.smart.rag.infrastructure.messaging.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * traceId 传播 round-trip（design §5 冻结点）：outbox INSERT 存储 publisher 线程的
 * traceparent；relay 重建 envelope 时用存储 headers、<b>不重新 inject</b>——
 * delegate send 对已存在 traceparent "不覆盖"，消费端收到的 traceparent = publisher 的。
 */
class OutboxTracePropagationTest extends AbstractRelayRoundTripTest {

    private static final String PUBLISHER_TRACEPARENT =
        "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    @Test
    @DisplayName("relay 投递的消息 traceparent = publisher 存储值（非 relay 自身注入）")
    void storedTraceparentPreserved() {
        insertFullRow(String.class.getName(), "\"doc\"", null, "h", null,
            Map.of("traceparent", PUBLISHER_TRACEPARENT, "content-type", "application/json"));

        drainAndCapture();

        assertThat(captured.get().headers().get("traceparent")).isEqualTo(PUBLISHER_TRACEPARENT);
        assertThat(captured.get().headers().get("content-type")).isEqualTo("application/json");
    }

    @Test
    @DisplayName("无 traceparent 的行：headers 保持原样（不凭空注入）")
    void absentTraceparentStaysAbsent() {
        insertFullRow(String.class.getName(), "\"doc\"", null, "h", null, Map.of());

        drainAndCapture();

        assertThat(captured.get().headers()).isEmpty();
    }
}
