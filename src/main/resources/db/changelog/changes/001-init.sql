--liquibase formatted sql

--changeset nechaev:init-extensions runOnChange:false
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
--rollback SELECT 1;

--changeset nechaev:init-vector-store
CREATE TABLE vector_store (
    id        UUID        DEFAULT uuid_generate_v4() PRIMARY KEY,
    content   TEXT,
    metadata  JSON,
    embedding VECTOR(384)
);
CREATE INDEX spring_ai_vector_index
    ON vector_store USING hnsw (embedding vector_cosine_ops);
--rollback DROP TABLE vector_store;

--changeset nechaev:init-ingestion-state
CREATE TABLE ingestion_state (
    source       VARCHAR(255)             PRIMARY KEY,
    content_hash VARCHAR(64)              NOT NULL,
    ingested_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
--rollback DROP TABLE ingestion_state;
