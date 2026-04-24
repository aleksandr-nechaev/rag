package com.nechaev.config;

import com.pgvector.PGvector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

// Registers PGvector deserializer on every new connection so vector columns can be read back.
// Wraps the raw non-pooled DataSource — HikariCP calls this only on physical connection creation.
class VectorAwareDataSource extends AbstractDataSource {

    private static final Logger log = LoggerFactory.getLogger(VectorAwareDataSource.class);

    private final DataSource delegate;

    VectorAwareDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return register(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return register(delegate.getConnection(username, password));
    }

    private Connection register(Connection conn) throws SQLException {
        try {
            PGvector.addVectorType(conn);
        } catch (Exception e) {
            log.warn("Failed to register PGvector type on connection: {}", e.getMessage());
        }
        return conn;
    }
}
