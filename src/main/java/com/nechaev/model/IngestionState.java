package com.nechaev.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "ingestion_state")
public class IngestionState {

    @Id
    @Column(name = "source", nullable = false, length = 255)
    private String source;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;

    protected IngestionState() {
    }

    public IngestionState(String source, String contentHash, Instant ingestedAt) {
        this.source = source;
        this.contentHash = contentHash;
        this.ingestedAt = ingestedAt;
    }

    public String getSource() {
        return source;
    }

    public String getContentHash() {
        return contentHash;
    }

    public Instant getIngestedAt() {
        return ingestedAt;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public void setIngestedAt(Instant ingestedAt) {
        this.ingestedAt = ingestedAt;
    }
}
