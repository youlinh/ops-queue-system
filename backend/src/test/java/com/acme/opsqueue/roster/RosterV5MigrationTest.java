package com.acme.opsqueue.roster;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RosterV5MigrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("roster_upgrade")
            .withUsername("roster_upgrade")
            .withPassword("test-password");

    @Test
    void upgradesARealV4SchemaAndBackfillsThenHardensRosterBatches() throws Exception {
        migrateTo("4");
        UUID userId = UUID.randomUUID();
        UUID emptyBatchId = UUID.randomUUID();
        UUID populatedBatchId = UUID.randomUUID();
        UUID rowId = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            try (var user = connection.prepareStatement("""
                    INSERT INTO users (id, username, display_name, password_hash, must_change_password, enabled, created_at, updated_at, version)
                    VALUES (UUID_TO_BIN(?), 'operator', 'Operator', 'hash', false, true, NOW(6), NOW(6), 0)
                    """)) {
                user.setString(1, userId.toString());
                user.executeUpdate();
            }
            insertBatch(connection, emptyBatchId, userId, "VALIDATED", 0);
            insertBatch(connection, populatedBatchId, userId, "VALIDATED", 1);
            try (var row = connection.prepareStatement("""
                    INSERT INTO roster_import_rows (id, batch_id, source_row_number, duty_date, second_line_user_id, third_line_user_id)
                    VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 2, '2026-07-25', UUID_TO_BIN(?), UUID_TO_BIN(?))
                    """)) {
                row.setString(1, rowId.toString());
                row.setString(2, populatedBatchId.toString());
                row.setString(3, userId.toString());
                // Use a second distinct referenced user to satisfy the V5 row check.
                UUID secondUser = UUID.randomUUID();
                try (var second = connection.prepareStatement("""
                        INSERT INTO users (id, username, display_name, password_hash, must_change_password, enabled, created_at, updated_at, version)
                        VALUES (UUID_TO_BIN(?), 'operator2', 'Operator 2', 'hash', false, true, NOW(6), NOW(6), 0)
                        """)) {
                    second.setString(1, secondUser.toString());
                    second.executeUpdate();
                }
                row.setString(4, secondUser.toString());
                row.executeUpdate();
            }
        }

        migrateTo("5");

        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                var statement = connection.prepareStatement("""
                        SELECT status, row_count, covered_dates, imported_by_user_id, imported_at
                        FROM roster_import_batches WHERE id = UUID_TO_BIN(?)
                        """)) {
            statement.setString(1, emptyBatchId.toString());
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("status")).isEqualTo("FAILED");
                assertThat(result.getInt("row_count")).isZero();
                assertThat(result.getString("covered_dates")).isEmpty();
                assertThat(result.getBytes("imported_by_user_id")).isNull();
                assertThat(result.getTimestamp("imported_at")).isNull();
            }
            statement.setString(1, populatedBatchId.toString());
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("status")).isEqualTo("VALIDATED");
                assertThat(result.getString("covered_dates")).isEqualTo("2026-07-25");
            }
        }
    }

    private void migrateTo(String target) {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    private void insertBatch(java.sql.Connection connection, UUID batchId, UUID userId, String status, int rowCount) throws Exception {
        try (var batch = connection.prepareStatement("""
                INSERT INTO roster_import_batches (id, status, original_filename, file_sha256, row_count, uploaded_by_user_id, created_at)
                VALUES (UUID_TO_BIN(?), ?, 'roster.xlsx', RPAD('', 64, '0'), ?, UUID_TO_BIN(?), NOW(6))
                """)) {
            batch.setString(1, batchId.toString());
            batch.setString(2, status);
            batch.setInt(3, rowCount);
            batch.setString(4, userId.toString());
            batch.executeUpdate();
        }
    }
}
