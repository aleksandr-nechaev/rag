package com.nechaev.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResumeIngestionServiceTest {

    @Mock VectorStore vectorStore;
    @Mock JdbcTemplate jdbcTemplate;

    ResumeIngestionService service;
    String realPdfHash;

    @BeforeEach
    void setUp() throws Exception {
        service = new ResumeIngestionService(vectorStore, jdbcTemplate);

        try (InputStream is = new ClassPathResource("static/Aleksandr Nechaev resume.pdf").getInputStream()) {
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
