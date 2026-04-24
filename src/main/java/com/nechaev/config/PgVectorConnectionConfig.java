package com.nechaev.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

@Configuration
public class PgVectorConnectionConfig {

    @Value("${spring.datasource.url}")
    private String url;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    // VectorAwareDataSource wraps the raw non-pooled DataSource, so HikariCP calls it only
    // when creating new physical connections — not on every pool checkout.
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource() {
        DriverManagerDataSource rawDs = new DriverManagerDataSource(url, username, password);
        HikariDataSource hikariDs = new HikariDataSource();
        hikariDs.setDataSource(new VectorAwareDataSource(rawDs));
        return hikariDs;
    }

    // @DependsOn guarantees the pgvector extension is installed by Liquibase before
    // any JdbcTemplate call is made. See VectorJdbcTemplate for the OID bypass strategy.
    @Bean
    @Primary
    @DependsOn("liquibase")
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new VectorJdbcTemplate(dataSource);
    }
}
