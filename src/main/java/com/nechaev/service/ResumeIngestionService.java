package com.nechaev.service;

import com.nechaev.config.AppProperties;
import com.nechaev.model.IngestionState;
import com.nechaev.repository.IngestionStateRepository;
import com.nechaev.repository.VectorStoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ResumeIngestionService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ResumeIngestionService.class);

    private static final String SOURCE = "resume";
    private static final String DEFAULT_SECTION = "HEADER";
    // Arbitrary fixed ID for pg_try_advisory_lock — unique per application concern.
    private static final long ADVISORY_LOCK_ID = 7_654_321L;

    private static final Pattern SECTION_HEADER = Pattern.compile(
            "(SUMMARY|SKILLS|FEATURED\\s+PROJECTS?|WORK\\s+EXPERIENCE|EDUCATION|PROJECTS?|CERTIFICATIONS?|LANGUAGES?|INTERESTS?)\\s*",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NEWLINE = Pattern.compile("\\r?\\n");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final VectorStore vectorStore;
    private final VectorStoreRepository vectorStoreRepository;
    private final IngestionStateRepository ingestionStateRepository;
    private final DistributedLockService lockService;
    private final TransactionTemplate transactionTemplate;
    private final String resumePath;

    public ResumeIngestionService(VectorStore vectorStore,
                                  VectorStoreRepository vectorStoreRepository,
                                  IngestionStateRepository ingestionStateRepository,
                                  DistributedLockService lockService,
                                  PlatformTransactionManager transactionManager,
                                  AppProperties appProperties) {
        this.vectorStore = vectorStore;
        this.vectorStoreRepository = vectorStoreRepository;
        this.ingestionStateRepository = ingestionStateRepository;
        this.lockService = lockService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.resumePath = appProperties.ingestion().resumePath();
    }

    @Override
    public void run(String... args) {
        boolean acquired = lockService.tryWithLock(ADVISORY_LOCK_ID, this::ingestIfChanged);
        if (!acquired) {
            log.info("Another instance is ingesting the resume, skipping.");
        }
    }

    private void ingestIfChanged() {
        byte[] pdfBytes = readPdfBytes();
        String currentHash = computeHash(pdfBytes);
        boolean unchanged = ingestionStateRepository.findById(SOURCE)
                .map(state -> state.getContentHash().equals(currentHash))
                .orElse(false);
        if (unchanged) {
            log.info("Resume unchanged, skipping ingestion.");
            return;
        }
        transactionTemplate.executeWithoutResult(status -> ingest(pdfBytes, currentHash));
    }

    private void ingest(byte[] pdfBytes, String hash) {
        log.info("Ingesting resume (hash: {}).", hash);

        vectorStoreRepository.deleteBySource(SOURCE);

        String fullText = new PagePdfDocumentReader(new ByteArrayResource(pdfBytes))
                .get().stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n"));

        List<Document> chunks = splitBySections(fullText);
        vectorStore.add(chunks);

        ingestionStateRepository.save(new IngestionState(SOURCE, hash, Instant.now()));

        log.info("Ingested {} sections from resume.", chunks.size());
    }

    private byte[] readPdfBytes() {
        try (InputStream is = new ClassPathResource(resumePath).getInputStream()) {
            return is.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read resume PDF", e);
        }
    }

    private static String computeHash(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot compute resume hash", e);
        }
    }

    private List<Document> splitBySections(String text) {
        List<Document> result = new ArrayList<>();
        String currentSection = DEFAULT_SECTION;
        StringBuilder currentContent = new StringBuilder();

        for (String line : NEWLINE.split(text)) {
            String stripped = line.strip();
            if (SECTION_HEADER.matcher(stripped).matches()) {
                flushSection(result, currentSection, currentContent.toString());
                currentSection = normalizeSectionName(stripped);
                currentContent = new StringBuilder();
            } else {
                currentContent.append(line).append("\n");
            }
        }
        flushSection(result, currentSection, currentContent.toString());

        return result;
    }

    private static String normalizeSectionName(String stripped) {
        return WHITESPACE.matcher(stripped.toUpperCase()).replaceAll(" ");
    }

    private void flushSection(List<Document> result, String section, String content) {
        String trimmed = content.strip();
        if (trimmed.isEmpty()) return;
        result.add(new Document(section + "\n" + trimmed, Map.of("section", section, "source", SOURCE)));
    }
}
