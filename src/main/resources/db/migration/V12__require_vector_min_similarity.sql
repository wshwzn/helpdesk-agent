UPDATE knowledge_bases
SET vector_min_similarity = 0.000
WHERE vector_min_similarity IS NULL;

ALTER TABLE knowledge_bases
    MODIFY COLUMN vector_min_similarity DECIMAL(4, 3) NOT NULL DEFAULT 0.000;
