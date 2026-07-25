CREATE TABLE roles (
    name VARCHAR(32) NOT NULL PRIMARY KEY
);

INSERT INTO roles (name) VALUES
    ('DEVELOPER'),
    ('OPERATOR'),
    ('LEADER');

CREATE TABLE users (
    id BINARY(16) NOT NULL PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    must_change_password BOOLEAN NOT NULL,
    enabled BOOLEAN NOT NULL,
    last_login_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_users_username UNIQUE (username)
);

CREATE TABLE user_roles (
    user_id BINARY(16) NOT NULL,
    role_name VARCHAR(32) NOT NULL,
    PRIMARY KEY (user_id, role_name),
    CONSTRAINT fk_user_roles_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role
        FOREIGN KEY (role_name) REFERENCES roles (name)
);
