package com.tally.payment.adapter.persistence;

import com.tally.payment.domain.MismatchKind;
import com.tally.payment.domain.SettlementBatch;
import com.tally.payment.domain.Settlements;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link Settlements} over Payment's own tables (V31). Every duplicate guard is named in its {@code ON CONFLICT}
 * (CLAUDE.md rule 11): any other failure is a failure, never a duplicate.
 */
@Component
class JdbcSettlements implements Settlements {

    private final JdbcTemplate jdbc;

    JdbcSettlements(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long lastImportedBatch() {
        return jdbc.queryForObject("SELECT coalesce(max(number), 0) FROM payment.settlement_batch", Long.class);
    }

    @Override
    public boolean markImported(long number, int lines, Instant now) {
        return jdbc.update("""
                INSERT INTO payment.settlement_batch (number, imported_at, lines) VALUES (?, ?, ?)
                ON CONFLICT ON CONSTRAINT settlement_batch_imported_once DO NOTHING
                """, number, now.atOffset(ZoneOffset.UTC), lines) == 1;
    }

    @Override
    public boolean addLine(long batch, SettlementBatch.Line line) {
        return jdbc.update("""
                INSERT INTO payment.settlement_line (charge_id, batch, reference, amount_minor, currency)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT settlement_line_one_per_charge DO NOTHING
                """, line.chargeId(), batch, line.reference(), line.amount().minorUnits(),
                line.amount().currency().getCurrencyCode()) == 1;
    }

    @Override
    public Optional<UUID> succeededWithCharge(String chargeId) {
        return jdbc.queryForList("SELECT id FROM payment.payment_attempt WHERE state = 'SUCCEEDED' AND charge_id = ?",
                UUID.class, chargeId).stream().findFirst();
    }

    @Override
    public List<Unsettled> succeededNotSettled(Instant sentBefore, int limit) {
        return jdbc.query("""
                SELECT a.id, a.reference FROM payment.payment_attempt a
                WHERE a.state = 'SUCCEEDED' AND a.sent_at < ?
                  AND NOT EXISTS (SELECT 1 FROM payment.settlement_line l WHERE l.charge_id = a.charge_id)
                ORDER BY a.sent_at LIMIT ?
                """, (rs, i) -> new Unsettled(rs.getObject(1, UUID.class), rs.getString(2)),
                sentBefore.atOffset(ZoneOffset.UTC), limit);
    }

    @Override
    public List<Unsettled> unknownNotSettled(Instant sentBefore, int limit) {
        return jdbc.query("""
                SELECT a.id, a.reference FROM payment.payment_attempt a
                WHERE a.state = 'UNKNOWN' AND a.sent_at < ?
                  AND NOT EXISTS (SELECT 1 FROM payment.settlement_line l WHERE l.reference = a.reference)
                ORDER BY a.sent_at LIMIT ?
                """, (rs, i) -> new Unsettled(rs.getObject(1, UUID.class), rs.getString(2)),
                sentBefore.atOffset(ZoneOffset.UTC), limit);
    }

    @Override
    public void addMismatchIfAbsent(MismatchKind kind, String subject, String reference, String detail, Instant now) {
        jdbc.update("""
                INSERT INTO payment.settlement_mismatch (id, kind, subject, reference, detail, found_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT settlement_mismatch_found_once DO NOTHING
                """, UUID.randomUUID(), kind.name(), subject, reference, detail, now.atOffset(ZoneOffset.UTC));
    }

    @Override
    public void closeMismatches(String subject, Instant now) {
        jdbc.update("UPDATE payment.settlement_mismatch SET resolved_at = ? WHERE subject = ? AND resolved_at IS NULL",
                now.atOffset(ZoneOffset.UTC), subject);
    }

    @Override
    public long openMismatches(MismatchKind kind) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM payment.settlement_mismatch WHERE kind = ? AND resolved_at IS NULL", Long.class,
                kind.name());
    }
}
