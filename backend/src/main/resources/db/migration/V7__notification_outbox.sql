CREATE TABLE notification_events (
    id BINARY(16) NOT NULL PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id BINARY(16) NOT NULL,
    recipient_user_id BINARY(16) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    retry_count INT NOT NULL,
    last_error TEXT NULL,
    next_attempt_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT chk_notification_events_type CHECK (event_type IN ('TASK_CALLED')),
    CONSTRAINT chk_notification_events_aggregate_type CHECK (aggregate_type IN ('TASK')),
    CONSTRAINT chk_notification_events_status CHECK (status IN ('NEW', 'SENDING', 'SENT', 'FAILED')),
    CONSTRAINT chk_notification_events_retry_count CHECK (retry_count >= 0),
    CONSTRAINT fk_notification_events_recipient
        FOREIGN KEY (recipient_user_id) REFERENCES users (id)
);

CREATE INDEX idx_notification_events_status_next_attempt
    ON notification_events (status, next_attempt_at);
CREATE INDEX idx_notification_events_aggregate_created
    ON notification_events (aggregate_type, aggregate_id, created_at);
