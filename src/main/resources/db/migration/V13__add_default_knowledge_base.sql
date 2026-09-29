ALTER TABLE knowledge_bases
    ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_knowledge_bases_default_updated
    ON knowledge_bases (is_default, updated_at);
