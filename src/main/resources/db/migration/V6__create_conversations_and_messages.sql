CREATE TABLE conversations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    last_message_at TIMESTAMP(6) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
);

CREATE INDEX idx_conversations_customer_status_updated
    ON conversations (customer_id, status, updated_at);

CREATE TABLE chat_messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    content MEDIUMTEXT NULL,
    status VARCHAR(20) NOT NULL,
    model_config_id BIGINT NULL,
    model_name VARCHAR(100) NULL,
    prompt_tokens INT NULL,
    completion_tokens INT NULL,
    total_tokens INT NULL,
    error_code VARCHAR(50) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
);

CREATE INDEX idx_chat_messages_conversation_id
    ON chat_messages (conversation_id, id);
