package com.nechaev.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.function.Supplier;

@Service
public class DistributedLockService {

    private static final Logger log = LoggerFactory.getLogger(DistributedLockService.class);

    private final DataSource dataSource;

    public DistributedLockService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public <T> Optional<T> tryWithLock(long lockId, Supplier<T> work) {
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try {
            if (!tryAcquire(conn, lockId)) {
                return Optional.empty();
            }
            try {
                return Optional.ofNullable(work.get());
            } finally {
                release(conn, lockId);
            }
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }

    public boolean tryWithLock(long lockId, Runnable work) {
        return tryWithLock(lockId, () -> {
            work.run();
            return Boolean.TRUE;
        }).isPresent();
    }

    private boolean tryAcquire(Connection conn, long lockId) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, lockId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to acquire advisory lock " + lockId, e);
        }
    }

    private void release(Connection conn, long lockId) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, lockId);
            try (ResultSet rs = ps.executeQuery()) {
                boolean released = rs.next() && rs.getBoolean(1);
                if (!released) {
                    log.warn("Advisory lock {} was not held at unlock time.", lockId);
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to release advisory lock {}.", lockId, e);
        }
    }
}
