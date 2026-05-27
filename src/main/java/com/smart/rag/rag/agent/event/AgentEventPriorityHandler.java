package com.smart.rag.rag.agent.event;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MyBatis TypeHandler -- {@link AgentEventPriority} 与 SMALLINT/INT 列互转
 */
public class AgentEventPriorityHandler extends BaseTypeHandler<AgentEventPriority> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, AgentEventPriority parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setInt(i, parameter.getValue());
    }

    @Override
    public AgentEventPriority getNullableResult(ResultSet rs, String columnName) throws SQLException {
        int value = rs.getInt(columnName);
        return rs.wasNull() ? null : AgentEventPriority.fromValue(value);
    }

    @Override
    public AgentEventPriority getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        int value = rs.getInt(columnIndex);
        return rs.wasNull() ? null : AgentEventPriority.fromValue(value);
    }

    @Override
    public AgentEventPriority getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        int value = cs.getInt(columnIndex);
        return cs.wasNull() ? null : AgentEventPriority.fromValue(value);
    }
}
