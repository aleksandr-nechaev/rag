package com.nechaev.config;

import com.pgvector.PGvector;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.SqlTypeValue;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;

// Bypasses TypeInfoCache.pgNameToOid for the "vector" type by sending PGvector values as
// untyped text (Types.OTHER / Oid.UNSPECIFIED). PostgreSQL infers the vector type from the
// column/operator context and parses it via vector_in, avoiding the OID lookup entirely.
//
// Only batchUpdate(String, BatchPreparedStatementSetter) and query(String, RowMapper, Object...)
// are PGvector-aware. If new write paths pass PGvector arguments through other JdbcTemplate
// overloads (e.g. update(String, Object...)), extend the overrides accordingly — otherwise
// those calls will fail with an OID-lookup error.
class VectorJdbcTemplate extends JdbcTemplate {

    VectorJdbcTemplate(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public int[] batchUpdate(String sql, BatchPreparedStatementSetter pss) {
        return super.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                pss.setValues(vectorProxy(ps), i);
            }

            @Override
            public int getBatchSize() {
                return pss.getBatchSize();
            }
        });
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, @Nullable Object... args) {
        return super.query(sql, rowMapper, convertArgs(args));
    }

    private static Object[] convertArgs(@Nullable Object[] args) {
        if (args == null) return null;
        return Arrays.stream(args)
                .map(arg -> arg instanceof PGvector pgv
                        ? new SqlParameterValue(Types.OTHER,
                                (SqlTypeValue) (ps, idx, sqlType, typeName) ->
                                        ps.setObject(idx, pgv.getValue(), Types.OTHER))
                        : arg)
                .toArray();
    }

    private static PreparedStatement vectorProxy(PreparedStatement ps) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class[]{PreparedStatement.class},
                (proxy, method, methodArgs) -> {
                    if ("setObject".equals(method.getName())
                            && methodArgs != null && methodArgs.length == 2
                            && methodArgs[1] instanceof PGvector pgv) {
                        ps.setObject((int) methodArgs[0], pgv.getValue(), Types.OTHER);
                        return null;
                    }
                    try {
                        return method.invoke(ps, methodArgs);
                    } catch (InvocationTargetException e) {
                        Throwable cause = e.getCause();
                        throw cause != null ? cause : e;
                    }
                });
    }
}
