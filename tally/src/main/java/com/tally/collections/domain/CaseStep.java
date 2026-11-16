package com.tally.collections.domain;

import com.tally.collections.CaseStatus;

/**
 * The steps of the timetable that change a case's state (design.md §5). Each is a timer on Collections' queue,
 * guarded by {@code case_step_one_per_case_and_step}. The retries are not steps: they change no state.
 */
public enum CaseStep {
    OVERDUE(CaseStatus.OVERDUE),
    BLOCKED(CaseStatus.BLOCKED),
    WRITTEN_OFF(CaseStatus.WRITTEN_OFF);

    private final CaseStatus state;

    CaseStep(CaseStatus state) {
        this.state = state;
    }

    /** The state the step moves a case to. */
    public CaseStatus state() {
        return state;
    }
}
