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

    // DriverManagerDataSource here is not a connection pool — it is a single-shot factory of
    // raw JDBC connections that HikariCP calls only on physical connection creation. The pooling
    // is done by the outer HikariDataSource. VectorAwareDataSource wraps the factory so that
    // PGvector type registration runs once per physical connection, not on every pool checkout.
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
