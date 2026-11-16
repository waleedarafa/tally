-- Slice 3 PR-4 (brief rules 1, 5 and 6; design.md section 5): the timetable and the standing. A case is OPEN, then
-- OVERDUE, BLOCKED and WRITTEN_OFF while a debt is open, and CLOSED when none of its invoices is unpaid.
--
-- Expand and contract (ADR-009), stated plainly: the previous release reads a case's status with JPA's valueOf, so
-- for the length of a rolling deploy it fails on a case in a new state (its read of the case, and its day-3 retry of
-- that case, which the queue tries again). Its listeners select OPEN cases only, so they never read one.
ALTER TABLE collections.collection_case DROP CONSTRAINT collection_case_status_check;
ALTER TABLE collections.collection_case ADD CONSTRAINT collection_case_status_check
    CHECK (status IN ('OPEN', 'OVERDUE', 'BLOCKED', 'WRITTEN_OFF', 'CLOSED'));

-- The guard of "one case per account" (CLAUDE.md rule 11) now covers every state with a debt open, so a decline while
-- a case is OVERDUE or BLOCKED joins it. The index of V24 (status = 'OPEN') stays: the previous release's insert names
-- it in its ON CONFLICT clause during a rolling deploy (ADR-009), and every row it guards this one guards too.
CREATE UNIQUE INDEX collection_case_one_unsettled_per_account ON collections.collection_case (account_id)
    WHERE status <> 'CLOSED';

-- Each step of a case's timetable, once: the timer queue's permanent duplicate guard (FR-037). OVERDUE, BLOCKED and
-- WRITTEN_OFF; the retries keep their own guard, case_retry_one_per_case_and_number.
CREATE TABLE collections.case_step (
    case_id UUID        NOT NULL REFERENCES collections.collection_case (id),
    step    TEXT        NOT NULL CHECK (step IN ('OVERDUE', 'BLOCKED', 'WRITTEN_OFF')),
    done_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT case_step_one_per_case_and_step UNIQUE (case_id, step)
);

CREATE TABLE collections.timer_queue (
    id              BIGSERIAL    PRIMARY KEY,
    queue_key       TEXT         NOT NULL UNIQUE,
    due_at          TIMESTAMPTZ  NOT NULL,
    payload         TEXT         NOT NULL,
    status          TEXT         NOT NULL CHECK (status IN ('READY', 'IN_PROCESSING', 'DONE', 'FAILED')),
    attempts        INT          NOT NULL DEFAULT 0,
    claim_token     UUID,
    claimed_at      TIMESTAMPTZ,
    claimed_by      TEXT,
    last_error      TEXT,
    last_success_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    finished_at     TIMESTAMPTZ
);
CREATE INDEX ON collections.timer_queue (due_at) WHERE status IN ('READY', 'IN_PROCESSING');
CREATE INDEX ON collections.timer_queue (finished_at);

-- The open cases of today had only their day-3 retry. They get the rest of the timetable, counted from their first
-- decline, with the default days of CollectionsTimetable: retry 2 on day 7, OVERDUE one hour later, BLOCKED on day 10,
-- WRITTEN_OFF on day 30. A step already past is due now, and its timer acts on the case as it finds it.
INSERT INTO collections.retry_queue (queue_key, due_at, payload, status, attempts, created_at)
SELECT id || ':2', opened_at + interval '7 days', id || ',2', 'READY', 0, now()
FROM collections.collection_case WHERE status = 'OPEN'
ON CONFLICT (queue_key) DO NOTHING;

INSERT INTO collections.timer_queue (queue_key, due_at, payload, status, attempts, created_at)
SELECT c.id || ':' || s.step, c.opened_at + s.after, c.id || ',' || s.step, 'READY', 0, now()
FROM collections.collection_case c
CROSS JOIN (VALUES ('OVERDUE', interval '7 days 1 hour'), ('BLOCKED', interval '10 days'),
                   ('WRITTEN_OFF', interval '30 days')) AS s (step, after)
WHERE c.status = 'OPEN';
