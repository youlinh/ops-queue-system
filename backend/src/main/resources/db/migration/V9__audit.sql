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

CREATE TABLE redistribution_audit_commands (
    id BINARY(16) NOT NULL PRIMARY KEY,
    actor_id BINARY(16) NOT NULL,
    source_operator_id BINARY(16) NOT NULL,
    operation_date DATE NOT NULL,
    task_count INT NOT NULL,
    processed_count INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    command_state VARCHAR(16) NOT NULL,
    source_ip VARCHAR(64) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    lease_until DATETIME(6) NOT NULL,
    CONSTRAINT chk_redistribution_audit_task_count
        CHECK (task_count >= 0),
    CONSTRAINT chk_redistribution_audit_success_count
        CHECK (success_count >= 0 AND success_count <= task_count),
    CONSTRAINT chk_redistribution_audit_processed_count
        CHECK (
            processed_count >= 0
            AND processed_count <= task_count
            AND success_count <= processed_count
        ),
    CONSTRAINT chk_redistribution_audit_state
        CHECK (command_state IN ('RUNNING', 'READY')),
    CONSTRAINT fk_redistribution_audit_actor
        FOREIGN KEY (actor_id) REFERENCES users (id),
    CONSTRAINT fk_redistribution_audit_source
        FOREIGN KEY (source_operator_id) REFERENCES users (id)
);

CREATE INDEX idx_redistribution_audit_created
    ON redistribution_audit_commands (created_at, id);
CREATE INDEX idx_redistribution_audit_state_lease
    ON redistribution_audit_commands (command_state, lease_until, id);

CREATE TRIGGER trg_audit_logs_no_update
BEFORE UPDATE ON audit_logs
FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_logs is append-only';

CREATE TRIGGER trg_audit_logs_no_delete
BEFORE DELETE ON audit_logs
FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_logs is append-only';
