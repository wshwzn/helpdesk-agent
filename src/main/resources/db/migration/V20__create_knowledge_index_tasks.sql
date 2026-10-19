ALTER TABLE knowledge_documents ADD COLUMN active_index_build_id VARCHAR(36) NULL;

CREATE TABLE knowledge_index_tasks (
    document_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL,
    run_token VARCHAR(36) NULL,
    available_at TIMESTAMP(6) NOT NULL,
    lease_until TIMESTAMP(6) NULL,
    error_code VARCHAR(50) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (document_id),
    CONSTRAINT fk_knowledge_index_task_document FOREIGN KEY (document_id)
        REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    CONSTRAINT ck_knowledge_index_task_status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_knowledge_index_task_attempts CHECK (attempt_count >= 0 AND max_attempts > 0)
);
CREATE INDEX idx_knowledge_index_task_available ON knowledge_index_tasks(status, available_at);
CREATE INDEX idx_knowledge_index_task_lease ON knowledge_index_tasks(status, lease_until);

-- 升级前须停止旧实例；旧内存任务不可恢复，显式标记失败后由管理员重新提交。
UPDATE knowledge_documents SET status = 'FAILED', chunk_count = 0,
    error_code = 'INDEX_TASK_MISSING', version = version + 1, updated_at = CURRENT_TIMESTAMP(6)
WHERE status = 'PROCESSING';
