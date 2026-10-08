-- A business table standing in for the permanent duplicate guard every queue must name (ADR-037).
CREATE TABLE platform_test.work_done (
    work_key TEXT NOT NULL CONSTRAINT work_done_one_per_key UNIQUE
);
