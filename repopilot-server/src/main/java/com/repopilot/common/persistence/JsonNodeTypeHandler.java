package com.repopilot.common.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MySQL {@code JSON} 列与 {@link JsonNode} 的映射。
 *
 * <p>刻意只保存 JSON 原文而不做二次结构转换：数据库负责语法校验，应用负责语义校验，
 * 避免"读写使用了不同的序列化配置导致内容漂移"。反序列化失败直接抛异常，
 * 不静默返回空对象——损坏的 JSON 必须暴露，而不是被当成 {}。
 */
@MappedTypes(JsonNode.class)
@MappedJdbcTypes({JdbcType.VARCHAR, JdbcType.LONGVARCHAR})
public class JsonNodeTypeHandler extends BaseTypeHandler<JsonNode> {

    /**
     * 仅用于数据库列编解码，与 MVC 层 ObjectMapper 的配置解耦：
     * 后者会随 API 演进调整（如日期格式），不应改变已落库的历史内容。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, JsonNode parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, parameter.toString());
    }

    @Override
    public JsonNode getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public JsonNode getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public JsonNode getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private JsonNode parse(String raw) throws SQLException {
        if (raw == null) {
            return null;
        }
        try {
            return MAPPER.readTree(raw);
        } catch (Exception ex) {
            throw new SQLException("JSON 列内容无法解析", ex);
        }
    }
}
