package com.nechaev.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import org.springframework.core.io.ByteArrayResource;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ResumeIngestionService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ResumeIngestionService.class);

    private static final String RESUME_PATH = "static/Aleksandr Nechaev resume.pdf";
    private static final String SOURCE = "resume";
    // Arbitrary fixed ID for pg_try_advisory_lock — unique per application concern.
    private static final long ADVISORY_LOCK_ID = 7_654_321L;

    private static final Pattern SECTION_HEADER = Pattern.compile(
            "^(SUMMARY|SKILLS|WORK\\s+EXPERIENCE|EDUCATION|PROJECTS?|CERTIFICATIONS?|LANGUAGES?|INTERESTS?)\\s*$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
    );

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;

    public ResumeIngestionService(VectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        if (!tryAcquireLock()) {
            log.info("Another instance is ingesting the resume, skipping.");
            return;
        }
        try {
            byte[] pdfBytes = readPdfBytes();
            String currentHash = computeHash(pdfBytes);
            boolean unchanged = storedHash().map(currentHash::equals).orElse(false);
            if (unchanged) {
                log.info("Resume unchanged, skipping ingestion.");
                return;
            }
            ingest(pdfBytes, currentHash);
        } finally {
            releaseLock();
        }
    }

    private void ingest(byte[] pdfBytes, String hash) {
        log.info("Ingesting resume (hash: {}).", hash);

        jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'source' = ?", SOURCE);

        String fullText = new PagePdfDocumentReader(new ByteArrayResource(pdfBytes))
                .get().stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n"));

        List<Document> chunks = splitBySections(fullText);
        vectorStore.add(chunks);

        jdbcTemplate.update("""
                INSERT INTO ingestion_state (source, content_hash)
                VALUES (?, ?)
                ON CONFLICT (source) DO UPDATE
                    SET content_hash = EXCLUDED.content_hash,
                        ingested_at  = now()
                """, SOURCE, hash);

        log.info("Ingested {} sections from resume.", chunks.size());
    }

    private Optional<String> storedHash() {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT content_hash FROM ingestion_state WHERE source = ?", String.class, SOURCE);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private boolean tryAcquireLock() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_lock(?)", Boolean.class, ADVISORY_LOCK_ID));
    }

    private void releaseLock() {
        jdbcTemplate.queryForObject(
                "SELECT pg_advisory_unlock(?)", Boolean.class, ADVISORY_LOCK_ID);
    }

    private byte[] readPdfBytes() {
        try (InputStream is = new ClassPathResource(RESUME_PATH).getInputStream()) {
            return is.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read resume PDF", e);
        }
    }

    private static String computeHash(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot compute resume hash", e);
        }
    }

    private List<Document> splitBySections(String text) {
        List<Document> result = new ArrayList<>();
        String currentSection = "HEADER";
        StringBuilder currentContent = new StringBuilder();

        for (String line : text.split("\\r?\\n")) {
            if (SECTION_HEADER.matcher(line.strip()).matches()) {
                flushSection(result, currentSection, currentContent.toString());
                currentSection = line.strip().toUpperCase().replaceAll("\\s+", " ");
                currentContent = new StringBuilder();
            } else {
                currentContent.append(line).append("\n");
            }
        }
        flushSection(result, currentSection, currentContent.toString());

        return result;
    }

    private void flushSection(List<Document> result, String section, String content) {
        String trimmed = content.strip();
        if (trimmed.isEmpty()) return;
        result.add(new Document(section + "\n" + trimmed, Map.of("section", section, "source", SOURCE)));
    }
}
