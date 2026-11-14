-- ADR-036: the "complete once" mark. One row at most, written the first time the copy
-- reached the end of the log an instance saw at start. An instance that finds it is ready at once, also while the log
-- is down (D13). An emptied database has no mark, so a rebuild waits for the catch-up.
CREATE TABLE access.copy_state (
    one         BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (one),
    complete_at TIMESTAMPTZ NOT NULL
);
