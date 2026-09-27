CREATE TABLE agent_tool_calls (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    assistant_message_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    call_id VARCHAR(64) NOT NULL,
    sequence_no INT NOT NULL,
    tool_name VARCHAR(100) NOT NULL,
    arguments_json MEDIUMTEXT NOT NULL,
    result_json MEDIUMTEXT NULL,
    status VARCHAR(20) NOT NULL,
    error_code VARCHAR(50) NULL,
    duration_ms BIGINT NULL,
    version INT NOT NULL DEFAULT 0,
    started_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_tool_calls_call_id UNIQUE (call_id),
    CONSTRAINT uk_agent_tool_calls_message_sequence UNIQUE (assistant_message_id, sequence_no)
);

CREATE INDEX idx_agent_tool_calls_conversation_id
    ON agent_tool_calls (conversation_id, id);

CREATE INDEX idx_agent_tool_calls_message_status_sequence
    ON agent_tool_calls (assistant_message_id, status, sequence_no);
