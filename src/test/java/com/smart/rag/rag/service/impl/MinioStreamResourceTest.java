package com.smart.rag.rag.service.impl;

import io.minio.GetObjectResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * W4 R1-M5: {@link MinioFileStorageService.MinioStreamResource#close()}
 * 委托关闭底层 {@link GetObjectResponse}，释放 MinIO HTTP 连接。
 */
@DisplayName("W4 R1-M5: MinioStreamResource.close() 关闭底层流")
class MinioStreamResourceTest {

    @Test
    @DisplayName("close() 关闭底层 GetObjectResponse")
    void closeDelegatesToUnderlyingResponse() throws Exception {
        GetObjectResponse response = mock(GetObjectResponse.class);
        MinioFileStorageService.MinioStreamResource resource =
                new MinioFileStorageService.MinioStreamResource(response, "bucket", "path/to/file.pdf");

        resource.close();

        verify(response).close();
    }
}
