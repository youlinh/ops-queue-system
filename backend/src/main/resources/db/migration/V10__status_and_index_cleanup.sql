-- The CALLED task status was never produced by the application: calling a task
-- moves it straight from PENDING to IN_PROGRESS (called_at records the moment).
-- Remove the dead state from the constraint so the schema matches reality.
UPDATE tasks SET status = 'IN_PROGRESS' WHERE status = 'CALLED';

ALTER TABLE tasks DROP CHECK chk_tasks_status;
ALTER TABLE tasks
    ADD CONSTRAINT chk_tasks_status
        CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED'));

-- (user_id, unavailable_date) is already the primary key; the extra unique
-- index duplicates it and only costs write time.
ALTER TABLE unavailability DROP INDEX uk_unavailability_user_date;
