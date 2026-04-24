package com.nechaev.service;

import com.nechaev.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResumeIngestionServiceTest {

    @Mock VectorStore vectorStore;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock PlatformTransactionManager transactionManager;

    ResumeIngestionService service;
    String realPdfHash;

    static final String RESUME_PATH = "static/Aleksandr Nechaev resume.pdf";

    @BeforeEach
    void setUp() throws Exception {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        AppProperties.Ingestion ingestion = new AppProperties.Ingestion(RESUME_PATH);
        AppProperties appProperties = new AppProperties(null, null, null, ingestion, null);
        service = new ResumeIngestionService(vectorStore, jdbcTemplate, transactionManager, appProperties);

        try (InputStream is = new ClassPathResource(RESUME_PATH).getInputStream()) {
            byte[] bytes = is.readAllBytes();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            realPdfHash = HexFormat.of().formatHex(digest);
        }
    }

    @Test
    void runLockNotAcquiredSkipsIngestion() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), anyLong()))
                .thenReturn(false);

        service.run();

        verify(jdbcTemplate, never()).queryForList(anyString(), eq(String.class), any());
        verify(vectorStore, never()).add(any());
    }

    @Test
    void runHashUnchangedSkipsIngestion() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), anyLong()))
                .thenReturn(true);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyString()))
                .thenReturn(List.of(realPdfHash));

        service.run();

        verify(vectorStore, never()).add(any());
        verify(jdbcTemplate, never()).update(anyString(), anyString(), anyString());
    }

    @Test
    void runNoStoredHashIngestsAndPersistsState() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), anyLong()))
                .thenReturn(true);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyString()))
                .thenReturn(List.of());

        service.run();

        verify(jdbcTemplate).update(
                anyString(), eq("resume"));
        verify(vectorStore).add(any());
        verify(jdbcTemplate).update(
                anyString(), eq("resume"), eq(realPdfHash));
    }

    @Test
    void runHashChangedDeletesOldAndIngestsNew() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), anyLong()))
                .thenReturn(true);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyString()))
                .thenReturn(List.of("old-hash-value"));

        service.run();

        verify(jdbcTemplate).update(
                anyString(), eq("resume"));
        verify(vectorStore).add(any());
    }

    @Test
    void runLockAlwaysReleasedAfterIngestion() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), anyLong()))
                .thenReturn(true);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyString()))
                .thenReturn(List.of());
        when(jdbcTemplate.update(anyString(), eq("resume")))
                .thenThrow(new RuntimeException("DB error"));

        try { service.run(); } catch (RuntimeException ignored) {}

        verify(jdbcTemplate).queryForObject(
                eq("SELECT pg_advisory_unlock(?)"), eq(Boolean.class), anyLong());
    }
}
