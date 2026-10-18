ALTER TABLE knowledge_documents
    ADD COLUMN source_key VARCHAR(120) NULL;
ALTER TABLE knowledge_documents
    ADD COLUMN business_version VARCHAR(40) NULL;
ALTER TABLE knowledge_documents
    ADD COLUMN applicability_scope VARCHAR(40) NOT NULL DEFAULT 'ALL';
ALTER TABLE knowledge_documents
    ADD COLUMN valid_from DATETIME(6) NULL;
ALTER TABLE knowledge_documents
    ADD COLUMN valid_until DATETIME(6) NULL;
ALTER TABLE knowledge_documents
    ADD COLUMN publication_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT';

ALTER TABLE knowledge_documents
    ADD CONSTRAINT uk_knowledge_document_business_version
        UNIQUE (knowledge_base_id, source_key, business_version, applicability_scope);
ALTER TABLE knowledge_documents
    ADD CONSTRAINT ck_knowledge_document_validity
        CHECK (valid_until IS NULL OR (valid_from IS NOT NULL AND valid_until > valid_from));
ALTER TABLE knowledge_documents
    ADD CONSTRAINT ck_knowledge_document_publication
        CHECK (publication_status IN ('DRAFT', 'PUBLISHED', 'WITHDRAWN'));

CREATE INDEX idx_knowledge_document_eligibility
    ON knowledge_documents (knowledge_base_id, publication_status, status, applicability_scope, valid_from);
