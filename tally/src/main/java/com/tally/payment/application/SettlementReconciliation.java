package com.tally.payment.application;

import com.tally.payment.AttemptState;
import com.tally.payment.domain.MismatchKind;
import com.tally.payment.domain.PaymentAttempt;
import com.tally.payment.domain.PaymentAttempts;
import com.tally.payment.domain.PaymentGateway;
import com.tally.payment.domain.SettlementBatch;
import com.tally.payment.domain.Settlements;
import com.tally.platform.PeriodicJob;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Settlement reconciliation (proposed 021, decided 2026-10-14; D14): Tally's attempts against the provider's
 * settlement batches, in both directions. It reads the new batches with no transaction open (FR-005), then
 * imports each batch, with all its lines and their consequences, in one transaction.
 * <ul>
 *   <li><b>Provider to Tally,</b> per settled charge: a SUCCEEDED attempt with that charge matches, if the amount
 *       does. Otherwise, an UNKNOWN attempt for the reference is decided by it: SUCCEEDED, through
 *       {@link Corrections}, as any other correction. Anything else is a mismatch.</li>
 *   <li><b>Tally to provider,</b> after {@code expected-within}: a SUCCEEDED attempt whose charge no batch shows is
 *       a mismatch; so is an UNKNOWN attempt with no charge for its reference. The UNKNOWN attempt is never
 *       decided by absence ("not found alone never means no charge", 026): a person decides.</li>
 * </ul>
 * A mismatch closes by itself when its cause goes away (a charge settles late); otherwise a person closes it.
 * A periodic job: runs on one instance at a time and reports its health (FR-023).
 */
@Component
class SettlementReconciliation implements PeriodicJob {

    private static final Logger log = LoggerFactory.getLogger(SettlementReconciliation.class);
    private static final int LIMIT = 500;

    private final PaymentGateway gateway;
    private final Settlements settlements;
    private final PaymentAttempts attempts;
    private final Corrections corrections;
    private final TransactionTemplate transactions;
    private final PaymentProperties properties;
    private final Clock clock;

    SettlementReconciliation(PaymentGateway gateway, Settlements settlements, PaymentAttempts attempts,
            Corrections corrections, TransactionTemplate transactions, PaymentProperties properties,
            MeterRegistry meters, Clock clock) {
        this.gateway = gateway;
        this.settlements = settlements;
        this.attempts = attempts;
        this.corrections = corrections;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
        for (MismatchKind kind : MismatchKind.values()) {
            Gauge.builder("tally.payment.settlement.mismatches", settlements, s -> s.openMismatches(kind))
                    .tag("kind", kind.name())
                    .description("Open settlement mismatches: Tally's payments and the provider's settlement"
                            + " disagree, and a person must look (D14). Runbook: docs/runbooks/settlement-mismatch.md")
                    .strongReference(true).register(meters);
        }
    }

    @Override
    public String name() {
        return "payment-settlement";
    }

    @Override
    public String table() {
        return "payment.jobs";
    }

    @Override
    public Duration interval() {
        return properties.settlement().interval();
    }

    /** Open mismatches: each waits for a person. */
    @Override
    public long waitingWork() {
        return Arrays.stream(MismatchKind.values()).mapToLong(settlements::openMismatches).sum();
    }

    @Override
    public void run() {
        List<SettlementBatch> batches = gateway.settlementBatchesAfter(settlements.lastImportedBatch());
        for (SettlementBatch batch : batches) {
            transactions.executeWithoutResult(tx -> importBatch(batch));
        }
        Instant cutoff = clock.instant().minus(properties.settlement().expectedWithin());
        transactions.executeWithoutResult(tx -> {
            Instant now = clock.instant();
            for (Settlements.Unsettled a : settlements.succeededNotSettled(cutoff, LIMIT)) {
                settlements.addMismatchIfAbsent(MismatchKind.RECORDED_NOT_SETTLED, a.attemptId().toString(),
                        a.reference(), "SUCCEEDED, and no settlement batch shows its charge", now);
            }
            for (Settlements.Unsettled a : settlements.unknownNotSettled(cutoff, LIMIT)) {
                settlements.addMismatchIfAbsent(MismatchKind.UNKNOWN_NOT_SETTLED, a.attemptId().toString(),
                        a.reference(), "UNKNOWN, and no settlement batch shows a charge for the reference:"
                                + " probably not charged; a person decides", now);
            }
        });
    }

    private void importBatch(SettlementBatch batch) {
        if (!settlements.markImported(batch.number(), batch.lines().size(), clock.instant())) {
            return;
        }
        for (SettlementBatch.Line line : batch.lines()) {
            if (settlements.addLine(batch.number(), line)) {
                match(line);
            } else {
                log.warn("Charge {} for {} settled again in batch {}; the first settlement stands", line.chargeId(),
                        line.reference(), batch.number());
            }
        }
    }

    private void match(SettlementBatch.Line line) {
        Instant now = clock.instant();
        Optional<UUID> recorded = settlements.succeededWithCharge(line.chargeId());
        if (recorded.isPresent()) {
            PaymentAttempt attempt = attempts.find(recorded.get()).orElseThrow();
            if (!attempt.amount().equals(line.amount())) {
                settlements.addMismatchIfAbsent(MismatchKind.AMOUNT_DIFFERS, line.chargeId(), line.reference(),
                        "settled " + line.amount() + ", recorded " + attempt.amount(), now);
            }
            settlements.closeMismatches(attempt.id().toString(), now);
            return;
        }
        Optional<PaymentAttempt> latest = attempts.latestFor(line.reference());
        if (latest.isPresent() && latest.get().state() == AttemptState.UNKNOWN) {
            PaymentAttempt unknown = latest.get();
            if (unknown.amount().equals(line.amount())) {
                corrections.charged(unknown, line.chargeId());
                settlements.closeMismatches(unknown.id().toString(), now);
            } else {
                settlements.addMismatchIfAbsent(MismatchKind.AMOUNT_DIFFERS, line.chargeId(), line.reference(),
                        "settled " + line.amount() + ", UNKNOWN attempt " + unknown.id() + " for " + unknown.amount()
                                + ": not decided", now);
            }
            return;
        }
        settlements.addMismatchIfAbsent(MismatchKind.CHARGED_NOT_RECORDED, line.chargeId(), line.reference(),
                "settled " + line.amount() + "; latest attempt: "
                        + latest.map(a -> a.state().name()).orElse("none"), now);
    }
}
