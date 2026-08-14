package com.smart.rag.rag.service.impl;

import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.event.DocumentDeletedEvent;
import com.smart.rag.rag.etl.Loader;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.service.FileStorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * DocumentLifecycleService 单元测试
 * <p>
 * 验证级联删除的资源清理编排，以及删除后 {@link DocumentDeletedEvent} 的发布。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentLifecycleService 单元测试")
class DocumentLifecycleServiceTest {

    @Mock
    private Loader vectorStoreLoader;

    @Mock
    private FileStorageService fileStorageService;

    @Mock
    private RagDocumentMapper ragDocumentMapper;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    /** ObjectProvider 桩：getIfAvailable 返回 null（模拟 EntityIndexCleanupService Bean 缺失） */
    private static org.springframework.beans.factory.ObjectProvider<EntityIndexCleanupService> noCleanupProvider() {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override public EntityIndexCleanupService getObject() { throw new java.util.NoSuchElementException("no bean"); }
            @Override public EntityIndexCleanupService getObject(Object... args) { return getObject(); }
            @Override public EntityIndexCleanupService getIfAvailable() { return null; }
            @Override public EntityIndexCleanupService getIfUnique() { return null; }
        };
    }

    private DocumentLifecycleService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new DocumentLifecycleService(vectorStoreLoader, fileStorageService,
                ragDocumentMapper, eventPublisher, noCleanupProvider());
    }

    @Test
    @DisplayName("cascadeDelete: DB 删除后发布 DocumentDeletedEvent")
    void cascadeDelete_publishesEvent() {
        RagDocument doc = new RagDocument();
        doc.setId(77L);
        doc.setBucket("b");
        doc.setStorageKey("k");

        service.cascadeDelete(doc);

        verify(ragDocumentMapper).deleteById(77L);
        ArgumentCaptor<DocumentDeletedEvent> captor = ArgumentCaptor.forClass(DocumentDeletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().documentId()).isEqualTo(77L);
    }

    @Test
    @DisplayName("cascadeDelete: 向量删除失败仍发布事件（容错，DB 已删除）")
    void cascadeDelete_vectorFailureStillPublishes() {
        RagDocument doc = new RagDocument();
        doc.setId(78L);
        doc.setBucket("b");
        doc.setStorageKey("k");
        doThrow(new RuntimeException("vector store down")).when(vectorStoreLoader).deleteByDocumentId(78L);

        service.cascadeDelete(doc);

        verify(ragDocumentMapper).deleteById(78L);
        verify(eventPublisher).publishEvent(new DocumentDeletedEvent(78L));
    }
}
