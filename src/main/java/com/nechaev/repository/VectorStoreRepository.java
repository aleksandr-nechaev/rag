package com.nechaev.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class VectorStoreRepository {

    private final JdbcClient jdbc;

    public VectorStoreRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public int deleteBySource(String source) {
        return jdbc.sql("DELETE FROM vector_store WHERE metadata->>'source' = ?")
                .param(source)
                .update();
    }
}
