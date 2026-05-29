--liquibase formatted sql

--changeset nechaev:resize-vector-store
DROP INDEX IF EXISTS spring_ai_vector_index;
DROP TABLE IF EXISTS vector_store;
CREATE TABLE vector_store (
    id        UUID        DEFAULT uuid_generate_v4() PRIMARY KEY,
    content   TEXT,
    metadata  JSON,
    embedding VECTOR(1536)
);
CREATE INDEX spring_ai_vector_index ON vector_store USING hnsw (embedding vector_cosine_ops);
--rollback DROP TABLE vector_store;

--changeset nechaev:reset-ingestion-state
DELETE FROM ingestion_state;
--rollback SELECT 1;
