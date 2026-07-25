ALTER TABLE users
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE identity_guards (
    guard_name VARCHAR(64) NOT NULL PRIMARY KEY
);

INSERT INTO identity_guards (guard_name) VALUES ('enabled-leader');
