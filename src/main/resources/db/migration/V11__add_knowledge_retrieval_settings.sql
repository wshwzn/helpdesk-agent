ALTER TABLE knowledge_bases
    ADD COLUMN retrieval_candidate_limit INT NOT NULL DEFAULT 50;

ALTER TABLE knowledge_bases
    ADD COLUMN vector_min_similarity DECIMAL(4, 3) NULL;

ALTER TABLE knowledge_bases
    ADD COLUMN retrieval_result_limit INT NOT NULL DEFAULT 5;
