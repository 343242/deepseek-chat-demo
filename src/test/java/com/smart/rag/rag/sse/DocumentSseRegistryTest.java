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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * DocumentSseRegistry 单元测试。
 * <p>
 * 验证 userId 路由、多 tab 连接、断开自动清理、心跳、在途感知的空闲收尾。
 */
@ExtendWith(MockitoExtension.class)
class DocumentSseRegistryTest {

    @Mock
    private SseEmitter emitter;

    private DocumentSseRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DocumentSseRegistry(60_000);
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

    @Test
    @DisplayName("在途文档存在时 heartbeat 不收尾（ETL 单阶段间隔可超宽限期，在途即豁免）")
    void heartbeat_keepsAliveWhileInFlight() throws IOException {
        registry = new DocumentSseRegistry(0);
        registry.register(1L, emitter);
        registry.send(new DocumentStatusChangedEvent(10L, 1L, null, EtlStatus.PARSING));
        clearInvocations(emitter);

        registry.heartbeat();

        verify(emitter, never()).complete();
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("在途转终态且过宽限期后 heartbeat 主动收尾")
    void heartbeat_completesAfterIdleGrace() throws IOException {
        registry = new DocumentSseRegistry(0);
        registry.register(1L, emitter);
        registry.send(new DocumentStatusChangedEvent(10L, 1L, null, EtlStatus.PARSING));
        registry.send(new DocumentStatusChangedEvent(10L, 1L, null, EtlStatus.COMPLETED));
        clearInvocations(emitter);

        registry.heartbeat();

        verify(emitter).complete();
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("无在途但在宽限期内（刚注册）只发心跳不收尾")
    void heartbeat_withinGraceSendsHeartbeatOnly() throws IOException {
        registry.register(1L, emitter);

        registry.heartbeat();

        verify(emitter, never()).complete();
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
    }
}
