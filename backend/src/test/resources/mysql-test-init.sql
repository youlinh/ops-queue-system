CREATE USER 'ops_runtime'@'%' IDENTIFIED BY 'runtime-test-password';
GRANT SELECT, INSERT, UPDATE, DELETE
    ON ops_queue.*
    TO 'ops_runtime'@'%';
