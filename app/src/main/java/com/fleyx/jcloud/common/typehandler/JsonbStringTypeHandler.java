package com.fleyx.jcloud.common.typehandler;

import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.apache.ibatis.type.TypeHandler;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * 将 Java String 与 PostgreSQL jsonb 列互转的类型处理器。
 * <p>
 * 远程挂载配置以 JSON 字符串形式在代码中维护，数据库使用 jsonb 存储。
 * 写入时使用 {@link Types#OTHER} 让 PostgreSQL 自动完成 jsonb 类型转换，
 * 读取时直接取字符串，避免编译期依赖 PostgreSQL JDBC 驱动中的 {@code PGobject}。
 */
@MappedTypes(String.class)
@MappedJdbcTypes(JdbcType.OTHER)
public class JsonbStringTypeHandler implements TypeHandler<String> {

    @Override
    public void setParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
        if (parameter == null) {
            ps.setNull(i, Types.OTHER);
            return;
        }
        ps.setObject(i, parameter, Types.OTHER);
    }

    @Override
    public String getResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    @Override
    public String getResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    @Override
    public String getResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }
}
