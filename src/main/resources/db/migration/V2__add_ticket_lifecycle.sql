ALTER TABLE tickets ADD COLUMN assignee_id BIGINT NULL;
ALTER TABLE tickets ADD COLUMN assigned_at TIMESTAMP(6) NULL;
ALTER TABLE tickets ADD COLUMN resolution VARCHAR(2000) NULL;
ALTER TABLE tickets ADD COLUMN resolved_at TIMESTAMP(6) NULL;
ALTER TABLE tickets ADD COLUMN cancel_reason VARCHAR(500) NULL;
ALTER TABLE tickets ADD COLUMN cancelled_at TIMESTAMP(6) NULL;
ALTER TABLE tickets ADD COLUMN version INT NOT NULL DEFAULT 0;

CREATE INDEX idx_tickets_assignee_status ON tickets (assignee_id, status);
