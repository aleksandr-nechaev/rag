package com.nechaev.service;

import com.nechaev.config.AppProperties;
import com.nechaev.model.IngestionState;
import com.nechaev.repository.IngestionStateRepository;
import com.nechaev.repository.VectorStoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.util.unit.DataSize;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResumeIngestionServiceTest {

    @Mock VectorStore vectorStore;
    @Mock VectorStoreRepository vectorStoreRepository;
    @Mock IngestionStateRepository ingestionStateRepository;
    @Mock DistributedLockService lockService;
    @Mock CacheEvictionService cacheEvictionService;
    @Mock PlatformTransactionManager transactionManager;

    ResumeIngestionService service;
    String realPdfHash;

    static final String RESUME_PATH = "static/Aleksandr Nechaev resume.pdf";

    @BeforeEach
    void setUp() throws Exception {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        AppProperties.Ingestion ingestion = new AppProperties.Ingestion(RESUME_PATH, DataSize.ofMegabytes(10));
        AppProperties appProperties = new AppProperties(null, null, null, ingestion, null, null, null);
        service = new ResumeIngestionService(
                vectorStore,
                vectorStoreRepository,
                ingestionStateRepository,
                lockService,
                cacheEvictionService,
                transactionManager,
                appProperties);

        try (InputStream is = new ClassPathResource(RESUME_PATH).getInputStream()) {
            byte[] bytes = is.readAllBytes();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            realPdfHash = HexFormat.of().formatHex(digest);
        }
    }

    @Test
    void runLockNotAcquiredSkipsIngestion() {
        when(lockService.tryWithLock(anyLong(), any(Runnable.class))).thenReturn(false);

        service.run();

        verify(ingestionStateRepository, never()).findById(any());
        verify(vectorStore, never()).add(any());
        verify(vectorStoreRepository, never()).deleteBySource(any());
        verify(cacheEvictionService, never()).evictAll();
    }

    @Test
    void runHashUnchangedSkipsIngestion() {
        givenLockAcquired();
        when(ingestionStateRepository.findById("resume"))
                .thenReturn(Optional.of(new IngestionState("resume", realPdfHash, java.time.Instant.now())));

        service.run();

        verify(vectorStore, never()).add(any());
        verify(vectorStoreRepository, never()).deleteBySource(any());
        verify(ingestionStateRepository, never()).save(any());
        verify(cacheEvictionService, never()).evictAll();
    }

    @Test
    void runNoStoredHashIngestsAndPersistsState() {
        givenLockAcquired();
        when(ingestionStateRepository.findById("resume")).thenReturn(Optional.empty());

        service.run();

        verify(vectorStoreRepository).deleteBySource("resume");
        verify(vectorStore).add(any());

        ArgumentCaptor<IngestionState> captor = ArgumentCaptor.forClass(IngestionState.class);
        verify(ingestionStateRepository).save(captor.capture());
        assertThat(captor.getValue().getSource()).isEqualTo("resume");
        assertThat(captor.getValue().getContentHash()).isEqualTo(realPdfHash);
        verify(cacheEvictionService).evictAll();
    }

    @Test
    void runHashChangedDeletesOldAndIngestsNew() {
        givenLockAcquired();
        when(ingestionStateRepository.findById("resume"))
                .thenReturn(Optional.of(new IngestionState("resume", "old-hash-value", java.time.Instant.now())));

        service.run();

        verify(vectorStoreRepository).deleteBySource("resume");
        verify(vectorStore).add(any());
        verify(ingestionStateRepository).save(any());
        verify(cacheEvictionService).evictAll();
    }

    private void givenLockAcquired() {
        when(lockService.tryWithLock(anyLong(), any(Runnable.class)))
                .thenAnswer(inv -> {
                    inv.<Runnable>getArgument(1).run();
                    return true;
                });
    }
}
