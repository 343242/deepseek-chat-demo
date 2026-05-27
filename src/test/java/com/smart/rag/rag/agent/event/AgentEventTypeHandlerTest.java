package com.smart.rag.rag.agent.event;

import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * AgentEventTypeHandler 单元测试。
 * <p>
 * 验证 MyBatis TypeHandler 将 AgentEventType 枚举与 VARCHAR 列互转。
 */
@ExtendWith(MockitoExtension.class)
class AgentEventTypeHandlerTest {

    private AgentEventTypeHandler handler;

    @Mock
    private PreparedStatement ps;

    @Mock
    private ResultSet rs;

    @Mock
    private CallableStatement cs;

    @BeforeEach
    void setUp() {
        handler = new AgentEventTypeHandler();
    }

    @Nested
    @DisplayName("setNonNullParameter")
    class SetNonNullParameter {

        @Test
        @DisplayName("将枚举转为字符串写入 PreparedStatement")
        void setParameter_writesEnumName() throws SQLException {
            handler.setNonNullParameter(ps, 1, AgentEventType.INTENT_CLASSIFIED, JdbcType.VARCHAR);

            verify(ps).setString(1, "INTENT_CLASSIFIED");
        }

        @Test
        @DisplayName("TOOL_CALLED 枚举正确写入")
        void setParameter_toolCalled() throws SQLException {
            handler.setNonNullParameter(ps, 2, AgentEventType.TOOL_CALLED, JdbcType.VARCHAR);

            verify(ps).setString(2, "TOOL_CALLED");
        }
    }

    @Nested
    @DisplayName("getNullableResult (ResultSet by column name)")
    class GetNullableResultByColumnName {

        @Test
        @DisplayName("将字符串转为枚举")
        void getString_returnsEnum() throws SQLException {
            when(rs.getString("event_type")).thenReturn("SELF_REFLECTION");

            AgentEventType result = handler.getNullableResult(rs, "event_type");

            assertThat(result).isEqualTo(AgentEventType.SELF_REFLECTION);
        }

        @Test
        @DisplayName("null 值返回 null")
        void getString_null_returnsNull() throws SQLException {
            when(rs.getString("event_type")).thenReturn(null);

            AgentEventType result = handler.getNullableResult(rs, "event_type");

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("getNullableResult (ResultSet by column index)")
    class GetNullableResultByColumnIndex {

        @Test
        @DisplayName("将字符串转为枚举")
        void getString_returnsEnum() throws SQLException {
            when(rs.getString(3)).thenReturn("TOOL_CALLED");

            AgentEventType result = handler.getNullableResult(rs, 3);

            assertThat(result).isEqualTo(AgentEventType.TOOL_CALLED);
        }

        @Test
        @DisplayName("null 值返回 null")
        void getString_null_returnsNull() throws SQLException {
            when(rs.getString(3)).thenReturn(null);

            AgentEventType result = handler.getNullableResult(rs, 3);

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("getNullableResult (CallableStatement)")
    class GetNullableResultCallable {

        @Test
        @DisplayName("将字符串转为枚举")
        void getString_returnsEnum() throws SQLException {
            when(cs.getString(1)).thenReturn("GUARDRAIL_TRIGGERED");

            AgentEventType result = handler.getNullableResult(cs, 1);

            assertThat(result).isEqualTo(AgentEventType.GUARDRAIL_TRIGGERED);
        }

        @Test
        @DisplayName("null 值返回 null")
        void getString_null_returnsNull() throws SQLException {
            when(cs.getString(1)).thenReturn(null);

            AgentEventType result = handler.getNullableResult(cs, 1);

            assertThat(result).isNull();
        }
    }
}
