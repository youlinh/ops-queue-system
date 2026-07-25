ALTER TABLE roster_import_batches
    ADD COLUMN covered_dates VARCHAR(4096) NOT NULL DEFAULT '',
    ADD CONSTRAINT chk_roster_import_batch_status CHECK (status IN ('VALIDATED', 'IMPORTED', 'FAILED')),
    ADD CONSTRAINT chk_roster_import_batch_metadata CHECK (
        (status = 'IMPORTED' AND imported_by_user_id IS NOT NULL AND imported_at IS NOT NULL AND row_count > 0)
        OR (status = 'VALIDATED' AND imported_by_user_id IS NULL AND imported_at IS NULL AND row_count > 0)
        OR (status = 'FAILED' AND imported_by_user_id IS NULL AND imported_at IS NULL AND row_count >= 0)
    );

ALTER TABLE roster_import_rows
    ADD CONSTRAINT chk_roster_import_row_positive CHECK (source_row_number > 0),
    ADD CONSTRAINT chk_roster_import_row_distinct_users CHECK (second_line_user_id <> third_line_user_id);

CREATE TABLE roster_import_errors (
    id BINARY(16) NOT NULL PRIMARY KEY,
    batch_id BINARY(16) NOT NULL,
    source_row_number INT NOT NULL,
    message VARCHAR(255) NOT NULL,
    CONSTRAINT chk_roster_import_error_row_positive CHECK (source_row_number > 0),
    CONSTRAINT fk_roster_import_errors_batch
        FOREIGN KEY (batch_id) REFERENCES roster_import_batches (id) ON DELETE CASCADE
);
