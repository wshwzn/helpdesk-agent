ALTER TABLE ai_model_configs
    ADD COLUMN model_type VARCHAR(20) NOT NULL DEFAULT 'CHAT';

ALTER TABLE ai_model_configs
    ADD COLUMN embedding_dimensions INT NULL;

CREATE INDEX idx_ai_model_configs_type_enabled_default
    ON ai_model_configs (model_type, enabled, is_default);
