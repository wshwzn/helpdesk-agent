CREATE TABLE knowledge_bases (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NULL,
    created_by BIGINT NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_knowledge_bases_name UNIQUE (name)
);

CREATE TABLE knowledge_documents (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_base_id BIGINT NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    storage_provider VARCHAR(30) NOT NULL,
    bucket_name VARCHAR(128) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_extension VARCHAR(20) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    chunk_count INT NOT NULL DEFAULT 0,
    error_code VARCHAR(50) NULL,
    created_by BIGINT NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_knowledge_documents_object_key UNIQUE (object_key)
);

CREATE INDEX idx_knowledge_documents_base_status_id
    ON knowledge_documents (knowledge_base_id, status, id);

CREATE INDEX idx_knowledge_documents_base_sha256
    ON knowledge_documents (knowledge_base_id, sha256);
