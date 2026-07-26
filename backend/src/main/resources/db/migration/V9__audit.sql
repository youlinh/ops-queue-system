CREATE TABLE audit_logs (
    id BINARY(16) NOT NULL PRIMARY KEY,
    actor_id BINARY(16) NOT NULL,
    action VARCHAR(64) NOT NULL,
    object_type VARCHAR(64) NOT NULL,
    object_id BINARY(16) NOT NULL,
    before_json JSON NOT NULL,
    after_json JSON NOT NULL,
    source_ip VARCHAR(64) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_audit_logs_actor
        FOREIGN KEY (actor_id) REFERENCES users (id)
);

CREATE INDEX idx_audit_logs_occurred
    ON audit_logs (occurred_at DESC, id DESC);
CREATE INDEX idx_audit_logs_action_time
    ON audit_logs (action, occurred_at DESC);
CREATE INDEX idx_audit_logs_object
    ON audit_logs (object_type, object_id, occurred_at DESC);
CREATE INDEX idx_audit_logs_actor_time
    ON audit_logs (actor_id, occurred_at DESC);
