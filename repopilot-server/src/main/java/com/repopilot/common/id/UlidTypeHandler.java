package com.repopilot.common.id;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * CHAR(26) 与 ULID 的映射，写入前校验格式。
 *
 * <p>数据库里 ULID 只是没有格式约束的定长字符串，写错一位后只会在排序与关联时表现为
 * "查不到"，很难在运行期定位。这个 TypeHandler 让非法主键在写入那一刻就失败。
 *
 * <p><b>刻意不做全局注册</b>：本类不带 {@code @MappedTypes}/{@code @MappedJdbcTypes}，
 * 也不放在 {@code mybatis.type-handlers-package} 扫描范围内。原因是库里还有 CHAR(64)
 * 的哈希列，若按"String + CHAR"全局注册，会把哈希值当成 ULID 校验并拒绝合法数据。
 * 因此只在 Mapper 的 id 参数上显式引用：
 * {@code #{id,typeHandler=com.repopilot.common.id.UlidTypeHandler}}。
 */
public class UlidTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, Ulid.requireValid(parameter));
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return check(rs.getString(columnName));
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return check(rs.getString(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return check(cs.getString(columnIndex));
    }

    private String check(String value) {
        return value == null ? null : Ulid.requireValid(value);
    }
}
