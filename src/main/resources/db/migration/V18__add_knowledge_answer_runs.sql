ALTER TABLE chat_messages ADD COLUMN knowledge_run_id VARCHAR(36) NULL;
ALTER TABLE chat_messages ADD CONSTRAINT uk_chat_messages_knowledge_run UNIQUE (knowledge_run_id);

CREATE TABLE knowledge_answer_runs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    assistant_message_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_code VARCHAR(50) NULL,
    revision BIGINT NOT NULL,
    snapshot MEDIUMTEXT NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    finished_at TIMESTAMP(6) NULL,
    CONSTRAINT uk_knowledge_answer_runs_run UNIQUE (run_id),
    CONSTRAINT uk_knowledge_answer_runs_message UNIQUE (assistant_message_id),
    CONSTRAINT ck_knowledge_answer_runs_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_knowledge_answer_runs_revision CHECK (revision > 0)
);
CREATE INDEX idx_knowledge_answer_runs_conversation ON knowledge_answer_runs (conversation_id, id);
CREATE INDEX idx_knowledge_answer_runs_status ON knowledge_answer_runs (status, id);
