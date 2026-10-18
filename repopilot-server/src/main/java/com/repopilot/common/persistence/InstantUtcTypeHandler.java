package com.repopilot.common.persistence;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * {@code DATETIME(3)} 与 {@link Instant} 的 UTC 映射。
 *
 * <p>JDBC 的时间转换默认依赖 JVM 时区与会话时区，两者不一致时会出现"写入 08:00、
 * 读回 00:00"这类偏差，且很难在开发机上复现。这里显式给驱动一个 UTC {@link Calendar}，
 * 让业务时刻在任何 JVM 默认时区、任何会话时区下都保持一致。
 *
 * <p>不使用静态共享 Calendar：{@link Calendar} 不是线程安全的，MyBatis 的 TypeHandler
 * 会被并发复用，共享实例会偶发错位。
 */
@MappedTypes(Instant.class)
public class InstantUtcTypeHandler extends BaseTypeHandler<Instant> {

    private static Calendar utcCalendar() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Instant parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setTimestamp(i, Timestamp.from(parameter), utcCalendar());
    }

    @Override
    public Instant getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toInstant(rs.getTimestamp(columnName, utcCalendar()));
    }

    @Override
    public Instant getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toInstant(rs.getTimestamp(columnIndex, utcCalendar()));
    }

    @Override
    public Instant getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toInstant(cs.getTimestamp(columnIndex, utcCalendar()));
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
