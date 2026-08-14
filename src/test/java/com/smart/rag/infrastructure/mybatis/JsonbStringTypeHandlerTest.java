package com.smart.rag.infrastructure.mybatis;

import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JsonbStringTypeHandler} 单元测试。
 * <p>
 * 核心回归点：写端必须以 {@link Types#OTHER} 绑定，否则驱动按 VARCHAR 发送，PostgreSQL 拒绝
 * varchar → jsonb 隐式转换（见 trace_event.documents INSERT 报错）。
 */
class JsonbStringTypeHandlerTest {

    private final JsonbStringTypeHandler handler = new JsonbStringTypeHandler();

    @Test
    @DisplayName("setNonNullParameter 以 Types.OTHER 绑定原字符串，交由 PG 按列(jsonb)推断")
    void setNonNullParameterBindsAsOther() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        String json = "[{\"docId\":1,\"score\":0.9}]";

        handler.setNonNullParameter(ps, 3, json, JdbcType.VARCHAR);

        verify(ps).setObject(eq(3), eq(json), eq(Types.OTHER));
    }

    @Test
    @DisplayName("getNullableResult(String) 直接回填 jsonb 列的 JSON 文本")
    void getNullableResultByColumnNameReturnsString() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("documents")).thenReturn("{\"k\":\"v\"}");

        assertThat(handler.getNullableResult(rs, "documents")).isEqualTo("{\"k\":\"v\"}");
    }

    @Test
    @DisplayName("getNullableResult(int) 支持按列序读取")
    void getNullableResultByColumnIndexReturnsString() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(9)).thenReturn("[1,2,3]");

        assertThat(handler.getNullableResult(rs, 9)).isEqualTo("[1,2,3]");
    }
}
