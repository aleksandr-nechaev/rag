package com.nechaev.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
public class PgVectorConnectionConfig {

    // @DependsOn guarantees the pgvector extension is installed by Liquibase before
    // any JdbcTemplate call is made. See VectorJdbcTemplate for the OID bypass strategy.
    @Bean
    @Primary
    @DependsOn("liquibase")
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new VectorJdbcTemplate(new VectorAwareDataSource(dataSource));
    }
}
