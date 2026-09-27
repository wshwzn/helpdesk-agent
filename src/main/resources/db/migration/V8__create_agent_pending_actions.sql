CREATE TABLE agent_pending_actions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    assistant_message_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    action_type VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL,
    title VARCHAR(100) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    priority VARCHAR(20) NOT NULL,
    priority_reason VARCHAR(500) NULL,
    ticket_id BIGINT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    executed_at TIMESTAMP(6) NULL,
    cancelled_at TIMESTAMP(6) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_pending_actions_message_type UNIQUE (assistant_message_id, action_type),
    CONSTRAINT uk_agent_pending_actions_ticket_id UNIQUE (ticket_id)
);

CREATE INDEX idx_agent_pending_actions_customer_conversation_status
    ON agent_pending_actions (customer_id, conversation_id, status, id);

CREATE INDEX idx_agent_pending_actions_status_expires
    ON agent_pending_actions (status, expires_at);
