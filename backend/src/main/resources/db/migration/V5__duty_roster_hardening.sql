ALTER TABLE roster_import_batches ADD COLUMN covered_dates MEDIUMTEXT NULL;

SET SESSION group_concat_max_len = 12582912;
UPDATE roster_import_batches batch
SET covered_dates = COALESCE((
    SELECT GROUP_CONCAT(import_row.duty_date ORDER BY import_row.duty_date SEPARATOR ',')
    FROM roster_import_rows import_row WHERE import_row.batch_id = batch.id
), '');
UPDATE roster_import_batches
SET status = 'FAILED', imported_by_user_id = NULL, imported_at = NULL
WHERE row_count = 0;
ALTER TABLE roster_import_batches
    MODIFY COLUMN covered_dates MEDIUMTEXT NOT NULL,
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
