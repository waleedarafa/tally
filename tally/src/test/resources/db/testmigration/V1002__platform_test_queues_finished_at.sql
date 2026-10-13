-- The test queue tables keep the shape QueueDefinition documents (V29).
ALTER TABLE platform_test.work_queue ADD COLUMN finished_at TIMESTAMPTZ;
ALTER TABLE platform_test.jobs ADD COLUMN finished_at TIMESTAMPTZ;
ALTER TABLE platform_test.polled_queue ADD COLUMN finished_at TIMESTAMPTZ;
ALTER TABLE platform_test.health_queue ADD COLUMN finished_at TIMESTAMPTZ;
ALTER TABLE platform_test.health_jobs ADD COLUMN finished_at TIMESTAMPTZ;
