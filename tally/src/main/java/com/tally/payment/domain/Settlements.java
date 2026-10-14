package com.tally.payment.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Imported settlement batches and the mismatches found in them (a port; {@code adapter.persistence} implements it). */
public interface Settlements {

    /** The highest batch number imported; 0 before the first. */
    long lastImportedBatch();

    /**
     * Records the batch as imported. False if it already was: a concurrent run imported it first (guard
     * {@code settlement_batch_imported_once}).
     */
    boolean markImported(long number, int lines, Instant now);

    /** Adds a settled charge. False if the charge settled before (guard {@code settlement_line_one_per_charge}). */
    boolean addLine(long batch, SettlementBatch.Line line);

    /** The attempt that recorded this charge as its success. */
    Optional<UUID> succeededWithCharge(String chargeId);

    /** SUCCEEDED attempts sent before {@code sentBefore} whose charge no imported batch shows. */
    List<Unsettled> succeededNotSettled(Instant sentBefore, int limit);

    /** UNKNOWN attempts sent before {@code sentBefore} whose reference no imported batch shows a charge for. */
    List<Unsettled> unknownNotSettled(Instant sentBefore, int limit);

    /** Records a mismatch, once per kind and subject (guard {@code settlement_mismatch_found_once}). */
    void addMismatchIfAbsent(MismatchKind kind, String subject, String reference, String detail, Instant now);

    /** Closes every open mismatch about {@code subject}: its cause has gone away. */
    void closeMismatches(String subject, Instant now);

    long openMismatches(MismatchKind kind);

    record Unsettled(UUID attemptId, String reference) {
    }
}
