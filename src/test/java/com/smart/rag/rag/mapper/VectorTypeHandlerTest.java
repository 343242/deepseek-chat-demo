package com.smart.rag.rag.mapper;

import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link VectorTypeHandler} — verifies the float[] ↔ pgvector text literal
 * serialization without requiring a live PostgreSQL / pgvector instance.
 * <p>
 * Limitation: no full PG integration test (would need testcontainers + pgvector extension).
 */
class VectorTypeHandlerTest {

    private VectorTypeHandler handler;

    @BeforeEach
    void setUp() {
        handler = new VectorTypeHandler();
    }

    // ── setNonNullParameter ──────────────────────────────────────────────

    @Nested
    @DisplayName("setNonNullParameter")
    class SetParam {

        @Test
        @DisplayName("single element → [val]")
        void singleElement() throws SQLException {
            PreparedStatement ps = mock(PreparedStatement.class);
            handler.setNonNullParameter(ps, 1, new float[]{0.5f}, JdbcType.OTHER);
            verify(ps).setString(eq(1), eq("[0.5]"));
        }

        @Test
        @DisplayName("multiple elements → [v1,v2,v3]")
        void multipleElements() throws SQLException {
            PreparedStatement ps = mock(PreparedStatement.class);
            handler.setNonNullParameter(ps, 2, new float[]{0.1f, -0.2f, 0.3f}, JdbcType.OTHER);
            verify(ps).setString(eq(2), eq("[0.1,-0.2,0.3]"));
        }

        @Test
        @DisplayName("empty array → []")
        void emptyArray() throws SQLException {
            PreparedStatement ps = mock(PreparedStatement.class);
            handler.setNonNullParameter(ps, 3, new float[0], JdbcType.OTHER);
            verify(ps).setString(eq(3), eq("[]"));
        }

        @Test
        @DisplayName("NaN and Infinity are preserved as text")
        void specialValues() throws SQLException {
            PreparedStatement ps = mock(PreparedStatement.class);
            handler.setNonNullParameter(ps, 1, new float[]{Float.NaN, Float.POSITIVE_INFINITY}, JdbcType.OTHER);
            verify(ps).setString(eq(1), eq("[NaN,Infinity]"));
        }
    }

    // ── getNullableResult (parse) ─────────────────────────────────────────

    @Nested
    @DisplayName("getNullableResult")
    class GetResult {

        @Test
        @DisplayName("null DB value → null")
        void nullValue() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("embedding")).thenReturn(null);
            assertNull(handler.getNullableResult(rs, "embedding"));
        }

        @Test
        @DisplayName("empty string → null")
        void emptyString() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("embedding")).thenReturn("");
            assertNull(handler.getNullableResult(rs, "embedding"));
        }

        @Test
        @DisplayName("blank string → null")
        void blankString() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("embedding")).thenReturn("   ");
            assertNull(handler.getNullableResult(rs, "embedding"));
        }

        @Test
        @DisplayName("empty brackets → empty array")
        void emptyBrackets() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("embedding")).thenReturn("[]");
            float[] result = handler.getNullableResult(rs, "embedding");
            assertNotNull(result);
            assertEquals(0, result.length);
        }

        @Test
        @DisplayName("[0.1,-0.2,0.3] → float[3]")
        void normalParse() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("embedding")).thenReturn("[0.1,-0.2,0.3]");
            float[] result = handler.getNullableResult(rs, "embedding");
            assertArrayEquals(new float[]{0.1f, -0.2f, 0.3f}, result, 1e-6f);
        }

        @Test
        @DisplayName("spaces around commas are tolerated")
        void spacedCommas() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("embedding")).thenReturn("[ 0.1 , -0.2 , 0.3 ]");
            float[] result = handler.getNullableResult(rs, "embedding");
            assertArrayEquals(new float[]{0.1f, -0.2f, 0.3f}, result, 1e-6f);
        }

        @Test
        @DisplayName("integer values parsed correctly")
        void integerValues() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("embedding")).thenReturn("[1,2,3]");
            float[] result = handler.getNullableResult(rs, "embedding");
            assertArrayEquals(new float[]{1.0f, 2.0f, 3.0f}, result, 1e-6f);
        }
    }
}
