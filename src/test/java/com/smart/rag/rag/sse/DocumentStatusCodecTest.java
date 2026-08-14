package com.smart.rag.rag.sse;

import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.event.DocumentStatusChangedEvent;
import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DocumentStatusCodec} 往返测试。
 * <p>
 * 直接守护根因修复：{@link DocumentStatusChangedEvent}（record）经 encode→decode 必须完整还原。
 * 在专用 codec 之前，全局 {@code JsonJacksonCodec}（NON_FINAL default typing）对 record 往返抛
 * {@code InvalidTypeIdException: missing type id property '@class'}。
 */
class DocumentStatusCodecTest {

    private final DocumentStatusCodec codec = new DocumentStatusCodec();

    @Test
    @DisplayName("record 经 encode→decode 往返，字段完整还原（teamId 非 null）")
    void roundTrip_preservesFields() throws Exception {
        DocumentStatusChangedEvent original = new DocumentStatusChangedEvent(10L, 1L, 7L, EtlStatus.COMPLETED);

        ByteBuf encoded = codec.getValueEncoder().encode(original);
        DocumentStatusChangedEvent decoded =
                (DocumentStatusChangedEvent) codec.getValueDecoder().decode(encoded, null);

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    @DisplayName("teamId=null（个人文档）往返保持 null")
    void roundTrip_preservesNullTeamId() throws Exception {
        DocumentStatusChangedEvent original = new DocumentStatusChangedEvent(11L, 2L, null, EtlStatus.FAILED);

        ByteBuf encoded = codec.getValueEncoder().encode(original);
        DocumentStatusChangedEvent decoded =
                (DocumentStatusChangedEvent) codec.getValueDecoder().decode(encoded, null);

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    @DisplayName("中间态状态（PARSING/VECTORIZING）同样可往返")
    void roundTrip_intermediateStatus() throws Exception {
        DocumentStatusChangedEvent original = new DocumentStatusChangedEvent(12L, 3L, null, EtlStatus.VECTORIZING);

        ByteBuf encoded = codec.getValueEncoder().encode(original);
        DocumentStatusChangedEvent decoded =
                (DocumentStatusChangedEvent) codec.getValueDecoder().decode(encoded, null);

        assertThat(decoded.status()).isEqualTo(EtlStatus.VECTORIZING);
        assertThat(decoded.documentId()).isEqualTo(12L);
    }
}
