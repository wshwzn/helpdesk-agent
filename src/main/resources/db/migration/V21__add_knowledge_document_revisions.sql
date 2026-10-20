ALTER TABLE knowledge_documents ADD COLUMN predecessor_document_id BIGINT NULL;
ALTER TABLE knowledge_documents ADD COLUMN successor_document_id BIGINT NULL;
ALTER TABLE knowledge_documents ADD COLUMN activated_at DATETIME(6) NULL;
ALTER TABLE knowledge_documents ADD COLUMN superseded_at DATETIME(6) NULL;

CREATE INDEX idx_knowledge_document_predecessor ON knowledge_documents(predecessor_document_id);
ALTER TABLE knowledge_documents ADD CONSTRAINT uk_knowledge_document_successor UNIQUE(successor_document_id);
ALTER TABLE knowledge_documents ADD CONSTRAINT fk_knowledge_document_predecessor
    FOREIGN KEY (predecessor_document_id) REFERENCES knowledge_documents(id);
ALTER TABLE knowledge_documents ADD CONSTRAINT fk_knowledge_document_successor
    FOREIGN KEY (successor_document_id) REFERENCES knowledge_documents(id);
