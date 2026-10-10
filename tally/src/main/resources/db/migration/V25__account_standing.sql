-- Slice 1R PR-8 (design.md section 4, F3): an account's standing, set only by Collections through
-- AccountStanding. Nothing changes it in Slice 1R: every account is GOOD.
ALTER TABLE account.account ADD COLUMN standing TEXT NOT NULL DEFAULT 'GOOD'
    CHECK (standing IN ('GOOD', 'OVERDUE', 'BLOCKED'));
ALTER TABLE account.account ADD COLUMN standing_cause TEXT;
