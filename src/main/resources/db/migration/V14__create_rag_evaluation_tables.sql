CREATE TABLE rag_evaluation_cases (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_base_id BIGINT NOT NULL,
    question VARCHAR(2000) NOT NULL,
    created_by BIGINT NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
);

CREATE INDEX idx_rag_evaluation_cases_base_id
    ON rag_evaluation_cases (knowledge_base_id, id);

CREATE TABLE rag_evaluation_case_documents (
    case_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (case_id, document_id)
);

CREATE INDEX idx_rag_evaluation_case_documents_document
    ON rag_evaluation_case_documents (document_id, case_id);

CREATE TABLE rag_evaluation_runs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_base_id BIGINT NOT NULL,
    candidate_limit INT NOT NULL,
    vector_min_similarity DECIMAL(4, 3) NOT NULL,
    result_limit INT NOT NULL,
    total_cases INT NOT NULL,
    hit_cases INT NOT NULL,
    hit_rate DECIMAL(8, 6) NOT NULL,
    recall_at_k DECIMAL(8, 6) NOT NULL,
    mean_reciprocal_rank DECIMAL(8, 6) NOT NULL,
    average_latency_ms BIGINT NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
);

CREATE INDEX idx_rag_evaluation_runs_base_created
    ON rag_evaluation_runs (knowledge_base_id, created_at, id);

CREATE TABLE rag_evaluation_run_results (
    id BIGINT NOT NULL AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    case_id BIGINT NOT NULL,
    question VARCHAR(2000) NOT NULL,
    expected_documents_json LONGTEXT NOT NULL,
    retrieved_results_json LONGTEXT NOT NULL,
    expected_document_count INT NOT NULL,
    matched_document_count INT NOT NULL,
    first_relevant_rank INT NULL,
    hit BOOLEAN NOT NULL,
    recall_at_k DECIMAL(8, 6) NOT NULL,
    reciprocal_rank DECIMAL(8, 6) NOT NULL,
    latency_ms BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
);

CREATE INDEX idx_rag_evaluation_run_results_run_case
    ON rag_evaluation_run_results (run_id, case_id);
