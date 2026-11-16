package com.tally.collections.application;

import com.tally.account.AccountStanding;
import com.tally.account.Standing;
import com.tally.collections.domain.CollectionCase;
import com.tally.collections.domain.CollectionCases;
import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.InvoiceView;
import com.tally.invoicing.InvoicingApi;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * What a case's state means for the account's standing, and when a case is settled (brief rules 4 and 5). Called in the
 * caller's transaction, under the case's row lock, so the standing follows the case in the same transaction: the end
 * state is never "case closed, standing BLOCKED". Only Collections sets the standing (R3, F3).
 */
@Component
class CaseSettlement {

    private final CollectionCases cases;
    private final InvoicingApi invoicing;
    private final AccountStanding standing;
    private final Clock clock;

    CaseSettlement(CollectionCases cases, InvoicingApi invoicing, AccountStanding standing, Clock clock) {
        this.cases = cases;
        this.invoicing = invoicing;
        this.standing = standing;
        this.clock = clock;
    }

    /** One of the case's invoices is paid. Closes the case if none is unpaid now; returns whether it closed. */
    boolean paid(CollectionCase locked, UUID invoiceId) {
        cases.markPaid(locked.id(), invoiceId, clock.instant());
        return closeIfNoneUnpaid(locked);
    }

    /**
     * A timer never acts on the case's memory alone (review of #78): asks Invoicing which of the case's invoices are
     * paid, marks them, and closes the case if none is unpaid. Repairs an {@code InvoicePaid} that was lost, or that
     * found no case because a repeated decline reopened one. Returns whether the case closed.
     */
    boolean settleWithInvoicing(CollectionCase locked) {
        Set<UUID> paid = invoicing.invoicesFor(locked.accountId()).stream()
                .filter(i -> i.status() == InvoiceStatus.PAID).map(InvoiceView::id).collect(Collectors.toSet());
        Instant now = clock.instant();
        cases.unpaidOf(locked.id()).stream().filter(paid::contains)
                .forEach(invoiceId -> cases.markPaid(locked.id(), invoiceId, now));
        return closeIfNoneUnpaid(locked);
    }

    private boolean closeIfNoneUnpaid(CollectionCase locked) {
        if (!locked.hasDebt() || !cases.unpaidOf(locked.id()).isEmpty()) {
            return false;
        }
        locked.close(clock.instant());
        follow(locked);
        return true;
    }

    /**
     * Sets the standing the case's state gives (design.md §5): OVERDUE gives OVERDUE; BLOCKED and WRITTEN_OFF give
     * BLOCKED (decision 4); CLOSED gives GOOD. OPEN never changed it. A standing already set changes nothing.
     */
    void follow(CollectionCase locked) {
        Standing target = switch (locked.status()) {
            case OVERDUE -> Standing.OVERDUE;
            case BLOCKED, WRITTEN_OFF -> Standing.BLOCKED;
            case CLOSED -> Standing.GOOD;
            case OPEN, UNKNOWN -> null;
        };
        if (target != null) {
            standing.set(locked.accountId(), target, "Collections case " + locked.id() + ": " + locked.status());
        }
    }
}
