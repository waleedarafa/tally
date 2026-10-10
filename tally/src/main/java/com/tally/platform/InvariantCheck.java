package com.tally.platform;

/**
 * A business invariant a module checks on its own data (FR-024: business facts are monitored, not
 * only errors). Declare it as a bean: the platform runs it as a periodic job in the module's job
 * table and reports {@code tally.invariant.violations{check}}.
 */
public interface InvariantCheck {

    /** Stable name, for example {@code invoicing.duplicate-invoices}: the {@code check} tag. */
    String name();

    /** The owning module's job table, schema-qualified (see {@link PeriodicJob#table()}). */
    String table();

    /** How many violations exist now. Zero when the invariant holds. */
    long violations();
}
