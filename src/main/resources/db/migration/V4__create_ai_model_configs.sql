CREATE TABLE ai_model_configs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    provider_type VARCHAR(40) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    api_key_ciphertext VARCHAR(2048) NOT NULL,
    api_key_hint VARCHAR(16) NOT NULL,
    encryption_key_version INT NOT NULL DEFAULT 1,
    supports_streaming BOOLEAN NOT NULL DEFAULT TRUE,
    supports_tools BOOLEAN NOT NULL DEFAULT TRUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    version INT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_ai_model_configs_name UNIQUE (name)
);

CREATE INDEX idx_ai_model_configs_enabled_default
    ON ai_model_configs (enabled, is_default);
