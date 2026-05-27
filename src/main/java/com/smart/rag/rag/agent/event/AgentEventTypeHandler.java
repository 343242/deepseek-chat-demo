package com.smart.rag.rag.agent.event;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MyBatis TypeHandler -- {@link AgentEventType} 与 VARCHAR 列互转
 */
public class AgentEventTypeHandler extends BaseTypeHandler<AgentEventType> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, AgentEventType parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, parameter.name());
    }

    @Override
    public AgentEventType getNullableResult(ResultSet rs, String columnName) throws SQLException {
        String value = rs.getString(columnName);
        return value != null ? AgentEventType.valueOf(value) : null;
    }

    @Override
    public AgentEventType getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        String value = rs.getString(columnIndex);
        return value != null ? AgentEventType.valueOf(value) : null;
    }

    @Override
    public AgentEventType getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        String value = cs.getString(columnIndex);
        return value != null ? AgentEventType.valueOf(value) : null;
    }
}
