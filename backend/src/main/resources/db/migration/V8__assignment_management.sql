ALTER TABLE tasks
    ADD COLUMN needs_manual_attention BOOLEAN NOT NULL DEFAULT FALSE
        AFTER completion_note;

CREATE INDEX idx_tasks_redistribution
    ON tasks (current_assignee_id, operation_date, status, ticket_number);
