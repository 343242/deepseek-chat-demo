package com.smart.rag.infrastructure.mybatis;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * MyBatis TypeHandler —— Java {@code String}（已是合法 JSON 文本）与 PostgreSQL {@code jsonb} 列互转。
 * <p>
 * <b>为什么需要它：</b>当实体字段是 {@code String}、列是 {@code jsonb}，且写入走 MyBatis-Plus
 * {@code BaseMapper.insert()} 自动生成的 SQL 时，驱动默认按 {@code VARCHAR} 绑定参数，PostgreSQL 会拒绝
 * {@code varchar → jsonb} 的隐式转换并报 {@code column "..." is of type jsonb but expression is of
 * type character varying}。手写 XML 里可用 {@code CAST(#{x} AS jsonb)} 绕过，但自动生成的 INSERT 无此能力。
 * <p>
 * <b>解法：</b>写端用 {@link PreparedStatement#setObject(int, Object, int) setObject(i, value, Types.OTHER)}
 * 绑定 —— 驱动据此发送“类型未指定”的字面量，由 PostgreSQL 按列类型（jsonb）推断，无需 PGobject、无需
 * 连接串 {@code stringtype=unspecified}，亦不污染全局 setString 行为。读端 {@code jsonb} 列经
 * {@code rs.getString} 即返回 JSON 文本，直接回填 String 字段。
 * <p>
 * 用法：实体上加 {@code @TableName(autoResultMap = true)}，字段标注
 * {@code @TableField(value = "col", typeHandler = JsonbStringTypeHandler.class)}。
 *
 * @see com.smart.rag.infrastructure.trace.TraceEvent#documents
 * @see com.smart.rag.agent.event.AgentSessionEvent#data
 */
public class JsonbStringTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        // Types.OTHER：让驱动以“未指定类型”发送，交由 PG 按列(jsonb)推断 —— 对 BaseMapper 自动生成 SQL 同样生效。
        ps.setObject(i, parameter, Types.OTHER);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }
}
