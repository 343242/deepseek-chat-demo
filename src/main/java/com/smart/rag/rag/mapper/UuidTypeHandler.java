package com.smart.rag.rag.mapper;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * MyBatis TypeHandler —— {@code java.util.UUID} ⇄ PostgreSQL {@code uuid} 列。
 * <p>
 * 背景：MyBatis 官方不内置 UUID handler（issue #1609 wontfix——UUID 存储格式因库而异），
 * registry 默认注册表亦不含 {@code UUID.class}。Path C 的 {@code votedChunk}/{@code expandedChunk}
 * resultMap 以 {@code javaType="java.util.UUID"} 映射 {@code rag_chunk_entity.chunk_id}（PG uuid 列），
 * 必须显式引用本 handler，否则启动时解析 resultMap 抛
 * {@code No typehandler found for property null}。
 * <p>
 * 写法：PG JDBC 对原生 uuid 列原生支持 {@code getObject(column, UUID.class)} /
 * {@code setObject(idx, UUID)}，无需字符串中转。
 */
public class UuidTypeHandler extends BaseTypeHandler<UUID> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, UUID parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, parameter);
    }

    @Override
    public UUID getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getObject(columnName, UUID.class);
    }

    @Override
    public UUID getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getObject(columnIndex, UUID.class);
    }

    @Override
    public UUID getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getObject(columnIndex, UUID.class);
    }
}
