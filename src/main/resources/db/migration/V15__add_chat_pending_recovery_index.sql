CREATE INDEX idx_chat_messages_status_role_updated
    ON chat_messages (status, role, updated_at);
