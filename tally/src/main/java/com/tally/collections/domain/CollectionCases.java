package com.tally.collections.domain;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Collections' cases and retries. Collections is layered, not ports and adapters (Design Record §10 C). */
public interface CollectionCases extends JpaRepository<CollectionCase, UUID> {

    /**
     * Opens a case for the account unless it has one with a debt open, atomically: the guard is the partial unique
     * index {@code collection_case_one_unsettled_per_account}, and only a clash on it is absorbed (CLAUDE.md rule 11).
     * Returns whether this call opened it.
     */
    default boolean openIfNone(UUID id, UUID accountId, UUID invoiceId, Instant now) {
        return insertOpenIfNone(id, accountId, invoiceId, now) == 1;
    }

    /**
     * Records retry {@code number} of the case. Returns whether this call recorded it: the guard is the
     * unique constraint {@code case_retry_one_per_case_and_number}, the retry queue's duplicate guard.
     */
    default boolean recordRetry(UUID caseId, int number, Instant now) {
        return insertRetry(caseId, number, now) == 1;
    }

    /**
     * The account's case with a debt open (OPEN, OVERDUE, BLOCKED or WRITTEN_OFF), locked until the caller's
     * transaction ends: a decline that joins the case, a payment that may close it and a timer wait for each other
     * (CLAUDE.md rule 7). Empty if it has none, also when the one this call waited for closed. The case's opening
     * invoice is in the case, also for a case the previous release opened (V39).
     */
    default Optional<CollectionCase> lockCaseWithDebt(UUID accountId) {
        return withOpeningInvoice(findWithDebtForUpdate(accountId));
    }

    /** The case, locked until the caller's transaction ends, as {@link #lockCaseWithDebt}; in any state. */
    default Optional<CollectionCase> lock(UUID caseId) {
        return withOpeningInvoice(findByIdForUpdate(caseId));
    }

    private Optional<CollectionCase> withOpeningInvoice(Optional<CollectionCase> locked) {
        locked.ifPresent(c -> insertInvoice(c.id(), c.invoiceId(), c.openedAt()));
        return locked;
    }

    /**
     * Records that the case took the step. Returns whether this call recorded it: the guard is the unique constraint
     * {@code case_step_one_per_case_and_step}, the timer queue's duplicate guard (CLAUDE.md rule 11).
     */
    default boolean recordStep(UUID caseId, CaseStep step, Instant now) {
        return insertStep(caseId, step.name(), now) == 1;
    }

    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO collections.case_step (case_id, step, done_at) VALUES (:caseId, :step, :now)
            ON CONFLICT ON CONSTRAINT case_step_one_per_case_and_step DO NOTHING
            """)
    int insertStep(UUID caseId, String step, Instant now);

    /**
     * Adds the invoice to the case, unpaid. Returns whether this call added it: the guard is the unique constraint
     * {@code case_invoice_one_per_case_and_invoice}, and only a clash on it is absorbed (CLAUDE.md rule 11).
     */
    default boolean addInvoice(UUID caseId, UUID invoiceId, Instant now) {
        return insertInvoice(caseId, invoiceId, now) == 1;
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CollectionCase c WHERE c.accountId = :accountId"
            + " AND c.status <> com.tally.collections.CaseStatus.CLOSED")
    Optional<CollectionCase> findWithDebtForUpdate(UUID accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CollectionCase c WHERE c.id = :caseId")
    Optional<CollectionCase> findByIdForUpdate(UUID caseId);

    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO collections.case_invoice (case_id, invoice_id, joined_at) VALUES (:caseId, :invoiceId, :now)
            ON CONFLICT ON CONSTRAINT case_invoice_one_per_case_and_invoice DO NOTHING
            """)
    int insertInvoice(UUID caseId, UUID invoiceId, Instant now);

    /** Marks the case's invoice paid; changes nothing if it is not in the case or is paid already. */
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE collections.case_invoice SET paid_at = :now
            WHERE case_id = :caseId AND invoice_id = :invoiceId AND paid_at IS NULL
            """)
    int markPaid(UUID caseId, UUID invoiceId, Instant now);

    /** The case's invoices, in the order they joined. */
    @Query(nativeQuery = true, value = """
            SELECT invoice_id FROM collections.case_invoice WHERE case_id = :caseId ORDER BY position
            """)
    List<UUID> invoicesOf(UUID caseId);

    /** The case's unpaid invoices, in the order they joined. */
    @Query(nativeQuery = true, value = """
            SELECT invoice_id FROM collections.case_invoice WHERE case_id = :caseId AND paid_at IS NULL
            ORDER BY position
            """)
    List<UUID> unpaidOf(UUID caseId);

    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO collections.collection_case (id, account_id, invoice_id, status, opened_at, version)
            VALUES (:id, :accountId, :invoiceId, 'OPEN', :now, 0)
            ON CONFLICT (account_id) WHERE status <> 'CLOSED' DO NOTHING
            """)
    int insertOpenIfNone(UUID id, UUID accountId, UUID invoiceId, Instant now);

    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO collections.case_retry (case_id, number, requested_at) VALUES (:caseId, :number, :now)
            ON CONFLICT ON CONSTRAINT case_retry_one_per_case_and_number DO NOTHING
            """)
    int insertRetry(UUID caseId, int number, Instant now);

    @Query(nativeQuery = true, value = "SELECT count(*) FROM collections.case_retry WHERE case_id = :caseId")
    int retriesOf(UUID caseId);

    /** The highest retry number recorded for the case; 0 if none. */
    @Query(nativeQuery = true, value = """
            SELECT coalesce(max(number), 0) FROM collections.case_retry WHERE case_id = :caseId
            """)
    int lastRetryOf(UUID caseId);

    List<CollectionCase> findByAccountIdOrderByOpenedAtDesc(UUID accountId);
}
