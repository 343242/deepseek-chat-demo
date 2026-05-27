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
 * AgentEventPriorityHandler 单元测试。
 * <p>
 * 验证 MyBatis TypeHandler 将 AgentEventPriority 枚举与 SMALLINT/INT 列互转。
 */
@ExtendWith(MockitoExtension.class)
class AgentEventPriorityHandlerTest {

    private AgentEventPriorityHandler handler;

    @Mock
    private PreparedStatement ps;

    @Mock
    private ResultSet rs;

    @Mock
    private CallableStatement cs;

    @BeforeEach
    void setUp() {
        handler = new AgentEventPriorityHandler();
    }

    @Nested
    @DisplayName("setNonNullParameter")
    class SetNonNullParameter {

        @Test
        @DisplayName("将 CRITICAL 枚举的 getValue() 写入 int")
        void setParameter_critical() throws SQLException {
            handler.setNonNullParameter(ps, 1, AgentEventPriority.CRITICAL, JdbcType.INTEGER);

            verify(ps).setInt(1, 1);
        }

        @Test
        @DisplayName("将 HIGH 枚举的 getValue() 写入 int")
        void setParameter_high() throws SQLException {
            handler.setNonNullParameter(ps, 2, AgentEventPriority.HIGH, JdbcType.INTEGER);

            verify(ps).setInt(2, 2);
        }

        @Test
        @DisplayName("将 NORMAL 枚举的 getValue() 写入 int")
        void setParameter_normal() throws SQLException {
            handler.setNonNullParameter(ps, 3, AgentEventPriority.NORMAL, JdbcType.INTEGER);

            verify(ps).setInt(3, 3);
        }
    }

    @Nested
    @DisplayName("getNullableResult (ResultSet by column name)")
    class GetNullableResultByColumnName {

        @Test
        @DisplayName("将 int 1 转为 CRITICAL")
        void getInt_critical() throws SQLException {
            when(rs.getInt("priority")).thenReturn(1);
            when(rs.wasNull()).thenReturn(false);

            AgentEventPriority result = handler.getNullableResult(rs, "priority");

            assertThat(result).isEqualTo(AgentEventPriority.CRITICAL);
        }

        @Test
        @DisplayName("SQL null 返回 null")
        void getInt_null_returnsNull() throws SQLException {
            when(rs.getInt("priority")).thenReturn(0);
            when(rs.wasNull()).thenReturn(true);

            AgentEventPriority result = handler.getNullableResult(rs, "priority");

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("getNullableResult (ResultSet by column index)")
    class GetNullableResultByColumnIndex {

        @Test
        @DisplayName("将 int 2 转为 HIGH")
        void getInt_high() throws SQLException {
            when(rs.getInt(2)).thenReturn(2);
            when(rs.wasNull()).thenReturn(false);

            AgentEventPriority result = handler.getNullableResult(rs, 2);

            assertThat(result).isEqualTo(AgentEventPriority.HIGH);
        }

        @Test
        @DisplayName("SQL null 返回 null")
        void getInt_null_returnsNull() throws SQLException {
            when(rs.getInt(2)).thenReturn(0);
            when(rs.wasNull()).thenReturn(true);

            AgentEventPriority result = handler.getNullableResult(rs, 2);

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("getNullableResult (CallableStatement)")
    class GetNullableResultCallable {

        @Test
        @DisplayName("将 int 3 转为 NORMAL")
        void getInt_normal() throws SQLException {
            when(cs.getInt(1)).thenReturn(3);
            when(cs.wasNull()).thenReturn(false);

            AgentEventPriority result = handler.getNullableResult(cs, 1);

            assertThat(result).isEqualTo(AgentEventPriority.NORMAL);
        }

        @Test
        @DisplayName("SQL null 返回 null")
        void getInt_null_returnsNull() throws SQLException {
            when(cs.getInt(1)).thenReturn(0);
            when(cs.wasNull()).thenReturn(true);

            AgentEventPriority result = handler.getNullableResult(cs, 1);

            assertThat(result).isNull();
        }
    }
}
