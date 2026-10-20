CREATE TABLE knowledge_sources (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    knowledge_base_id BIGINT NOT NULL,
    bucket_name VARCHAR(255) NOT NULL,
    object_prefix VARCHAR(120) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT NOT NULL,
    scan_status VARCHAR(20) NOT NULL DEFAULT 'IDLE',
    run_token VARCHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    last_scan_at DATETIME(6) NULL,
    error_code VARCHAR(80) NULL,
    added_count INT NOT NULL DEFAULT 0,
    updated_count INT NOT NULL DEFAULT 0,
    unchanged_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    missing_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_knowledge_source_prefix UNIQUE(bucket_name, object_prefix),
    CONSTRAINT fk_knowledge_source_base FOREIGN KEY(knowledge_base_id) REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_source_creator FOREIGN KEY(created_by) REFERENCES users(id),
    CONSTRAINT ck_knowledge_source_status CHECK(scan_status IN ('IDLE', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED'))
);
CREATE INDEX idx_knowledge_source_base ON knowledge_sources(knowledge_base_id);

CREATE TABLE knowledge_source_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_id BIGINT NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    key_hash VARCHAR(64) NOT NULL,
    accepted_fingerprint VARCHAR(64) NULL,
    last_document_id BIGINT NULL,
    last_seen_token VARCHAR(36) NULL,
    item_status VARCHAR(20) NOT NULL DEFAULT 'ERROR',
    error_code VARCHAR(80) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_knowledge_source_item UNIQUE(source_id, key_hash),
    CONSTRAINT fk_knowledge_source_item_source FOREIGN KEY(source_id) REFERENCES knowledge_sources(id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_source_item_document FOREIGN KEY(last_document_id) REFERENCES knowledge_documents(id),
    CONSTRAINT ck_knowledge_source_item_status CHECK(item_status IN ('SYNCED', 'ERROR', 'MISSING'))
);
ALTER TABLE knowledge_documents ADD COLUMN source_item_id BIGINT NULL;
ALTER TABLE knowledge_documents ADD CONSTRAINT fk_knowledge_document_source_item
    FOREIGN KEY(source_item_id) REFERENCES knowledge_source_items(id);
CREATE INDEX idx_knowledge_document_source_item ON knowledge_documents(source_item_id);
