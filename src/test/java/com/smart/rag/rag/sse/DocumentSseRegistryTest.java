package com.smart.rag.rag.sse;

import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.event.DocumentStatusChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * DocumentSseRegistry 单元测试。
 * <p>
 * 验证 userId 路由、多 tab 连接、断开自动清理、心跳。
 */
@ExtendWith(MockitoExtension.class)
class DocumentSseRegistryTest {

    @Mock
    private SseEmitter emitter;

    private DocumentSseRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DocumentSseRegistry();
    }

    @Test
    @DisplayName("register 后追踪到活跃用户连接")
    void register_tracksConnection() {
        registry.register(1L, emitter);
        assertThat(registry.userCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("send 向已注册用户推送 status 事件帧")
    void send_deliversToRegisteredUser() throws IOException {
        registry.register(1L, emitter);
        DocumentStatusChangedEvent event = new DocumentStatusChangedEvent(10L, 1L, null, EtlStatus.COMPLETED);

        registry.send(event);

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("无连接时 send 静默跳过（跨实例下大多数实例走此路径）")
    void send_skipsWhenNoConnection() throws IOException {
        DocumentStatusChangedEvent event = new DocumentStatusChangedEvent(10L, 999L, null, EtlStatus.COMPLETED);

        assertThatCode(() -> registry.send(event)).doesNotThrowAnyException();
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("send 时客户端断开（IOException）则自动清理连接")
    void send_removesDisconnectedEmitter() throws IOException {
        registry.register(1L, emitter);
        doThrow(new IOException("disconnected")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        DocumentStatusChangedEvent event = new DocumentStatusChangedEvent(10L, 1L, null, EtlStatus.PARSING);

        registry.send(event);

        assertThat(registry.userCount()).isZero();
    }

    @Test
    @DisplayName("heartbeat 向所有连接发注释帧")
    void heartbeat_sendsToAll() throws IOException {
        registry.register(1L, emitter);

        registry.heartbeat();

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("heartbeat 时断开的连接自动清理")
    void heartbeat_cleansDisconnected() throws IOException {
        registry.register(1L, emitter);
        doThrow(new IOException("disconnected")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        registry.heartbeat();

        assertThat(registry.userCount()).isZero();
    }

    @Test
    @DisplayName("同一用户多 tab 各自持有连接，send 扇出到全部")
    void send_fanOutMultipleTabs() throws IOException {
        SseEmitter emitter2 = mock(SseEmitter.class);
        registry.register(1L, emitter);
        registry.register(1L, emitter2);

        DocumentStatusChangedEvent event = new DocumentStatusChangedEvent(10L, 1L, null, EtlStatus.COMPLETED);
        registry.send(event);

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter2).send(any(SseEmitter.SseEventBuilder.class));
    }
}
