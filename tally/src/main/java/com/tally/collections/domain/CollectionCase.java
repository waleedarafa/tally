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
 * One Collections case: one open case per account (design.md §5), opened on a declined invoice payment,
 * closed when that invoice is paid. Opened by an atomic insert ({@link CollectionCases#openIfNone}), so
 * two declines delivered at the same time open one case (CLAUDE.md rule 7).
 */
@Entity
@Table(schema = "collections", name = "collection_case")
public class CollectionCase {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    /** The invoice whose payment was declined: the one Collections asks to be collected again. */
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

    /** Closes the case. Returns false, changing nothing, if it is not open. */
    public boolean close(Instant now) {
        if (status != CaseStatus.OPEN) {
            return false;
        }
        status = CaseStatus.CLOSED;
        closedAt = now;
        return true;
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
