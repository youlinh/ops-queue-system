CREATE TABLE duty_rosters (
    id BINARY(16) NOT NULL PRIMARY KEY,
    duty_date DATE NOT NULL,
    second_line_user_id BINARY(16) NOT NULL,
    third_line_user_id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_duty_rosters_duty_date UNIQUE (duty_date),
    CONSTRAINT chk_duty_rosters_distinct_users CHECK (second_line_user_id <> third_line_user_id),
    CONSTRAINT fk_duty_rosters_second_line_user
        FOREIGN KEY (second_line_user_id) REFERENCES users (id),
    CONSTRAINT fk_duty_rosters_third_line_user
        FOREIGN KEY (third_line_user_id) REFERENCES users (id)
);

CREATE TABLE roster_import_batches (
    id BINARY(16) NOT NULL PRIMARY KEY,
    status VARCHAR(16) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    file_sha256 VARCHAR(64) NOT NULL,
    row_count INT NOT NULL,
    uploaded_by_user_id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    imported_by_user_id BINARY(16) NULL,
    imported_at DATETIME(6) NULL,
    CONSTRAINT fk_roster_import_batches_uploaded_by
        FOREIGN KEY (uploaded_by_user_id) REFERENCES users (id),
    CONSTRAINT fk_roster_import_batches_imported_by
        FOREIGN KEY (imported_by_user_id) REFERENCES users (id)
);

CREATE TABLE roster_import_rows (
    id BINARY(16) NOT NULL PRIMARY KEY,
    batch_id BINARY(16) NOT NULL,
    source_row_number INT NOT NULL,
    duty_date DATE NOT NULL,
    second_line_user_id BINARY(16) NOT NULL,
    third_line_user_id BINARY(16) NOT NULL,
    CONSTRAINT uk_roster_import_rows_batch_date UNIQUE (batch_id, duty_date),
    CONSTRAINT fk_roster_import_rows_batch
        FOREIGN KEY (batch_id) REFERENCES roster_import_batches (id) ON DELETE CASCADE,
    CONSTRAINT fk_roster_import_rows_second_line_user
        FOREIGN KEY (second_line_user_id) REFERENCES users (id),
    CONSTRAINT fk_roster_import_rows_third_line_user
        FOREIGN KEY (third_line_user_id) REFERENCES users (id)
);

CREATE INDEX idx_roster_import_batches_created_at ON roster_import_batches (created_at DESC);
