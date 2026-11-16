package com.tally.collections.domain;

/**
 * The retries of the timetable (design.md §5): day 3 and day 7, counted from the case's first decline. A scheduled
 * retry is known by its step, not by its number: the case's next number is taken when it runs (#80, option B).
 */
public enum RetryStep {
    DAY_3,
    DAY_7
}
