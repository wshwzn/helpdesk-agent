ALTER TABLE ai_model_configs
    RENAME COLUMN provider_type TO protocol;

ALTER TABLE ai_model_configs
    ADD COLUMN vendor VARCHAR(40) NOT NULL DEFAULT 'CUSTOM';
