CREATE TABLE schema_marker (
    id TINYINT NOT NULL PRIMARY KEY,
    marker VARCHAR(64) NOT NULL
);

INSERT INTO schema_marker (id, marker) VALUES (1, 'bootstrap');
