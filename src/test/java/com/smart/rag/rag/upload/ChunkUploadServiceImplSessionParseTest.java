package com.smart.rag.rag.upload;

import com.smart.rag.infrastructure.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W5 R1-M2: ChunkUploadServiceImpl session 字段安全解析 —— 非法格式不再抛
 * NumberFormatException(→500)，而是干净的 ServiceException(UPLOAD_SESSION_NOT_FOUND)。
 * 纯函数单测，无需构造 service 依赖。
 */
@DisplayName("W5 R1-M2: session 字段安全解析")
class ChunkUploadServiceImplSessionParseTest {

    @Test
    @DisplayName("parseSessionLong 合法值 → 返回")
    void parseSessionLongValid() {
        assertThat(ChunkUploadServiceImpl.parseSessionLong(Map.of("userId", "42"), "userId")).isEqualTo(42L);
    }

    @Test
    @DisplayName("parseSessionLong 缺失 / 非法 → ServiceException")
    void parseSessionLongInvalid() {
        assertThatThrownBy(() -> ChunkUploadServiceImpl.parseSessionLong(Map.of(), "userId"))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ChunkUploadServiceImpl.parseSessionLong(Map.of("userId", "abc"), "userId"))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    @DisplayName("parseSessionInt 合法 / 非法")
    void parseSessionInt() {
        assertThat(ChunkUploadServiceImpl.parseSessionInt(Map.of("totalChunks", "5"), "totalChunks")).isEqualTo(5);
        assertThatThrownBy(() -> ChunkUploadServiceImpl.parseSessionInt(Map.of("totalChunks", "x"), "totalChunks"))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    @DisplayName("parseNullableLong: null→null, 合法→值, 非法→ServiceException")
    void parseNullableLong() {
        assertThat(ChunkUploadServiceImpl.parseNullableLong(null, "teamId")).isNull();
        assertThat(ChunkUploadServiceImpl.parseNullableLong("7", "teamId")).isEqualTo(7L);
        assertThatThrownBy(() -> ChunkUploadServiceImpl.parseNullableLong("bad", "teamId"))
                .isInstanceOf(ServiceException.class);
    }
}
