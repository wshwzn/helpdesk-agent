CREATE TABLE tickets (
    id BIGINT NOT NULL AUTO_INCREMENT,
    ticket_no VARCHAR(32) NOT NULL,
    title VARCHAR(100) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    status VARCHAR(20) NOT NULL,
    priority VARCHAR(20) NOT NULL,
    creator_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_tickets_ticket_no UNIQUE (ticket_no)
);

CREATE INDEX idx_tickets_creator_status ON tickets (creator_id, status);
