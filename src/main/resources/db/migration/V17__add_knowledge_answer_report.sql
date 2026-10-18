ALTER TABLE chat_messages
    ADD COLUMN mode VARCHAR(20) NOT NULL DEFAULT 'AGENT';
ALTER TABLE chat_messages
    ADD COLUMN applicability_scope VARCHAR(40) NULL;
ALTER TABLE chat_messages
    ADD COLUMN knowledge_answer MEDIUMTEXT NULL;
ALTER TABLE chat_messages
    ADD CONSTRAINT ck_chat_message_mode CHECK (mode IN ('AGENT', 'KNOWLEDGE'));
