package com.smart.rag.rag.etl;

import com.smart.rag.rag.parser.DocumentParser;
import com.smart.rag.rag.parser.DocumentParserFactory;
import com.smart.rag.rag.service.FileStorageService;
import com.smart.rag.rag.service.ObjectReadRange;
import com.smart.rag.rag.service.StoredObjectContent;
import com.smart.rag.rag.service.StoredObjectHandle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.Resource;

import java.io.Closeable;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * W4 R1-M5: {@link DocumentExtractor#extract} parser 抛异常时仍关闭底层 Resource，
 * 释放 MinIO HTTP 连接（不泄漏）。经统一 open(...).content(Full) 契约读取。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("W4 R1-M5: DocumentExtractor parser 异常时关闭 Resource")
class DocumentExtractorTest {

    @Mock private FileStorageService fileStorageService;
    @Mock private DocumentParserFactory parserFactory;
    @Mock private DocumentParser parser;
    @Mock private StoredObjectHandle handle;

    /** 模拟惰性 MinIO Resource —— 同时是 Resource 和 Closeable */
    private Resource closableResource;

    @BeforeEach
    void setUp() {
        closableResource = mock(Resource.class, withSettings().extraInterfaces(Closeable.class));
        when(fileStorageService.open(anyString(), anyString())).thenReturn(handle);
        when(handle.content(any(ObjectReadRange.class)))
                .thenReturn(new StoredObjectContent(closableResource, 0, 10));
        when(parserFactory.getParser(anyString())).thenReturn(parser);
        // parser 解析中途抛异常 —— 底层 MinIO 连接不能泄漏
        when(parser.parse(any(Resource.class), anyString()))
                .thenThrow(new RuntimeException("parser boom"));
    }

    @Test
    @DisplayName("parser 抛异常 → finally 关闭 Resource（连接不泄漏）")
    void closesResourceOnParserFailure() throws Exception {
        DocumentExtractor extractor = new DocumentExtractor(fileStorageService, parserFactory);

        assertThatThrownBy(() -> extractor.extract("bucket", "key", "application/pdf"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("parser boom");

        // R1-M5: finally 块关闭了 Closeable Resource，异常路径不泄漏连接
        verify((Closeable) closableResource).close();
    }
}
