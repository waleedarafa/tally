package com.tally.collections.domain;

import com.tally.collections.CaseStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * One Collections case: one open case per account (design.md §5), opened on a declined invoice payment.
 * It covers every unpaid invoice of the account (Slice 3 PR-3; {@code collections.case_invoice}), and closes when
 * none is unpaid. Opened by an atomic insert ({@link CollectionCases#openIfNone}), so
 * two declines delivered at the same time open one case (CLAUDE.md rule 7).
 */
@Entity
@Table(schema = "collections", name = "collection_case")
public class CollectionCase {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    /** The invoice whose decline opened the case. The case's invoices are in {@code collections.case_invoice}. */
    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CaseStatus status;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected CollectionCase() {
    }

    /** Closes the case: none of its invoices is unpaid. Returns false, changing nothing, if it is closed already. */
    public boolean close(Instant now) {
        if (!hasDebt()) {
            return false;
        }
        status = CaseStatus.CLOSED;
        closedAt = now;
        return true;
    }

    /**
     * A step of the timetable, by its rule, not by one start state (review of #76): it moves a case with a debt open
     * forward to its state, and never back. The day-10 step blocks a case that is OPEN or OVERDUE; the day-30 step
     * writes off one that is OPEN, OVERDUE or BLOCKED. Returns false, changing nothing, if the case is there already,
     * further on, or closed.
     */
    public boolean take(CaseStep step) {
        if (!hasDebt() || rank(status) >= rank(step.state())) {
            return false;
        }
        status = step.state();
        return true;
    }

    /** Every state but CLOSED has a debt open. */
    public boolean hasDebt() {
        return status != CaseStatus.CLOSED;
    }

    private static int rank(CaseStatus status) {
        return switch (status) {
            case OPEN -> 0;
            case OVERDUE -> 1;
            case BLOCKED -> 2;
            case WRITTEN_OFF -> 3;
            case CLOSED, UNKNOWN -> throw new IllegalStateException("No rank for " + status);
        };
    }

    public UUID id() {
        return id;
    }

    public UUID accountId() {
        return accountId;
    }

    public UUID invoiceId() {
        return invoiceId;
    }

    public CaseStatus status() {
        return status;
    }

    public Instant openedAt() {
        return openedAt;
    }

    public Instant closedAt() {
        return closedAt;
    }
}
