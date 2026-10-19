ALTER TABLE knowledge_bases ADD COLUMN clarification_policy MEDIUMTEXT NULL;
ALTER TABLE knowledge_bases ADD COLUMN clarification_revision BIGINT NOT NULL DEFAULT 0;
