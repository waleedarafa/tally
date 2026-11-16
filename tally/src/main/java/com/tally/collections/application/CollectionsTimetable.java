package com.tally.collections.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The Collections timetable (Design Record §7 "Collections", rule 1; H4), counted from the case's first decline:
 * retry 1, retry 2, OVERDUE one hour after retry 2 is asked (D2's measure: an unknown payment is resolved within one
 * hour), BLOCKED, WRITTEN_OFF. Finance owns the days; they are settings, and V40 used the defaults for the cases it
 * scheduled.
 */
@ConfigurationProperties("tally.collections.timetable")
public record CollectionsTimetable(
        @DefaultValue("3d") Duration firstRetry,
        @DefaultValue("7d") Duration secondRetry,
        @DefaultValue("1h") Duration overdueAfterSecondRetry,
        @DefaultValue("10d") Duration blocked,
        @DefaultValue("30d") Duration writtenOff) {

    Duration overdue() {
        return secondRetry.plus(overdueAfterSecondRetry);
    }
}
