-- Slice 3 PR-5, option B of the #80 review (confirmed 16 November 2026): one sequence of retry numbers for each case.
-- Every request of a case, scheduled or "pay now", takes the next number at the moment it is asked, under the case's
-- row lock, and the number is stored with the row, so a run after a crash uses it again. A scheduled retry is known by
-- its step (day 3, day 7), not by its number: its permanent guard is one row for each case and step (rule 11). A
-- "pay now" request has no step; its guard stays one row for each case and number.
ALTER TABLE collections.case_retry ADD COLUMN step TEXT CHECK (step IN ('DAY_3', 'DAY_7'));

-- The rows of today are the timetable's: retry 1 was day 3, retry 2 was day 7 (ADR-009 keeps them).
UPDATE collections.case_retry SET step = 'DAY_3' WHERE number = 1;
UPDATE collections.case_retry SET step = 'DAY_7' WHERE number = 2;

CREATE UNIQUE INDEX case_retry_one_per_case_and_step ON collections.case_retry (case_id, step) WHERE step IS NOT NULL;
