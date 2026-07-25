CREATE TABLE tasks (
    id BINARY(16) NOT NULL PRIMARY KEY,
    ticket_number VARCHAR(32) NOT NULL,
    category VARCHAR(32) NOT NULL,
    system_name VARCHAR(128) NOT NULL,
    estimated_minutes INT NOT NULL,
    process_number VARCHAR(128) NOT NULL,
    operation_date DATE NOT NULL,
    operation_start_at DATETIME(6) NOT NULL,
    operation_end_at DATETIME(6) NOT NULL,
    creator_id BINARY(16) NOT NULL,
    current_assignee_id BINARY(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    auto_assignment_rule VARCHAR(64) NOT NULL,
    auto_assignment_explanation TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    called_at DATETIME(6) NULL,
    called_by_user_id BINARY(16) NULL,
    actual_start_at DATETIME(6) NULL,
    actual_end_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    completed_by_user_id BINARY(16) NULL,
    completion_note TEXT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_tasks_ticket_number UNIQUE (ticket_number),
    CONSTRAINT chk_tasks_category
        CHECK (category IN ('VERSION_RELEASE', 'DATA_MAINTENANCE')),
    CONSTRAINT chk_tasks_estimated_minutes CHECK (estimated_minutes > 0),
    CONSTRAINT chk_tasks_operation_range CHECK (operation_end_at > operation_start_at),
    CONSTRAINT chk_tasks_status
        CHECK (status IN ('PENDING', 'CALLED', 'IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT fk_tasks_creator
        FOREIGN KEY (creator_id) REFERENCES users (id),
    CONSTRAINT fk_tasks_current_assignee
        FOREIGN KEY (current_assignee_id) REFERENCES users (id),
    CONSTRAINT fk_tasks_called_by
        FOREIGN KEY (called_by_user_id) REFERENCES users (id),
    CONSTRAINT fk_tasks_completed_by
        FOREIGN KEY (completed_by_user_id) REFERENCES users (id)
);

CREATE INDEX idx_tasks_operation_date_start
    ON tasks (operation_date, operation_start_at);
CREATE INDEX idx_tasks_status ON tasks (status);
CREATE INDEX idx_tasks_creator ON tasks (creator_id);
CREATE INDEX idx_tasks_assignee ON tasks (current_assignee_id);
CREATE INDEX idx_tasks_category ON tasks (category);
CREATE INDEX idx_tasks_system_name ON tasks (system_name);

CREATE TABLE assignment_histories (
    id BINARY(16) NOT NULL PRIMARY KEY,
    task_id BINARY(16) NOT NULL,
    assignment_type VARCHAR(32) NOT NULL,
    old_assignee_id BINARY(16) NULL,
    new_assignee_id BINARY(16) NOT NULL,
    assignment_rule VARCHAR(64) NOT NULL,
    reason TEXT NOT NULL,
    candidate_snapshot JSON NOT NULL,
    actor_id BINARY(16) NOT NULL,
    assigned_at DATETIME(6) NOT NULL,
    CONSTRAINT chk_assignment_histories_type
        CHECK (assignment_type IN ('AUTO', 'TRANSFER', 'REASSIGN')),
    CONSTRAINT fk_assignment_histories_task
        FOREIGN KEY (task_id) REFERENCES tasks (id) ON DELETE CASCADE,
    CONSTRAINT fk_assignment_histories_old_assignee
        FOREIGN KEY (old_assignee_id) REFERENCES users (id),
    CONSTRAINT fk_assignment_histories_new_assignee
        FOREIGN KEY (new_assignee_id) REFERENCES users (id),
    CONSTRAINT fk_assignment_histories_actor
        FOREIGN KEY (actor_id) REFERENCES users (id)
);

CREATE INDEX idx_assignment_histories_task_time
    ON assignment_histories (task_id, assigned_at);
CREATE INDEX idx_assignment_histories_new_assignee_time
    ON assignment_histories (new_assignee_id, assigned_at);

CREATE TABLE unavailability (
    user_id BINARY(16) NOT NULL,
    unavailable_date DATE NOT NULL,
    reason VARCHAR(255) NOT NULL,
    created_by_user_id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, unavailable_date),
    CONSTRAINT uk_unavailability_user_date
        UNIQUE (user_id, unavailable_date),
    CONSTRAINT fk_unavailability_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_unavailability_creator
        FOREIGN KEY (created_by_user_id) REFERENCES users (id)
);

CREATE INDEX idx_unavailability_date
    ON unavailability (unavailable_date);

CREATE TABLE schedule_date_locks (
    business_date DATE NOT NULL PRIMARY KEY
);

CREATE TABLE daily_ticket_sequences (
    issue_date DATE NOT NULL PRIMARY KEY,
    last_sequence INT NOT NULL,
    CONSTRAINT chk_daily_ticket_sequences_nonnegative
        CHECK (last_sequence >= 0)
);
