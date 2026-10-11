package com.tally.collections.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Collections' cases and retries. Collections is layered, not ports and adapters (Design Record §10 C). */
public interface CollectionCases extends JpaRepository<CollectionCase, UUID> {

    /**
     * Opens a case for the account unless one is open, atomically: the guard is the partial unique index
     * {@code collection_case_one_open_per_account}, and only a clash on it is absorbed (CLAUDE.md rule 11).
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

    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO collections.collection_case (id, account_id, invoice_id, status, opened_at, version)
            VALUES (:id, :accountId, :invoiceId, 'OPEN', :now, 0)
            ON CONFLICT (account_id) WHERE status = 'OPEN' DO NOTHING
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

    Optional<CollectionCase> findByAccountIdAndInvoiceIdAndStatus(UUID accountId, UUID invoiceId,
            com.tally.collections.CaseStatus status);

    List<CollectionCase> findByAccountIdOrderByOpenedAtDesc(UUID accountId);
}
