package com.tally.invoicing.adapter.persistence;

import com.tally.invoicing.domain.TaxQuote;
import com.tally.invoicing.domain.UpgradeIntent;
import com.tally.invoicing.domain.UpgradeIntents;
import com.tally.shared.Ratio;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** {@link UpgradeIntents} over {@code invoicing.upgrade_intent} (V47). */
@Component
class JdbcUpgradeIntents implements UpgradeIntents {

    private final JdbcTemplate jdbc;

    JdbcUpgradeIntents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(UpgradeIntent intent) {
        TaxQuote tax = intent.tax();
        boolean zero = tax.equals(TaxQuote.zero());
        jdbc.update("""
                INSERT INTO invoicing.upgrade_intent (payment_reference, subscription_id, account_id, new_plan_id,
                    new_offer_version, period_invoice_id, quote, completed_at,
                    tax_rate_numerator, tax_rate_denominator, tax_description)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?)
                ON CONFLICT (payment_reference) DO NOTHING
                """, intent.paymentReference(), intent.subscriptionId(), intent.accountId(), intent.newPlanId(),
                intent.newOfferVersion(), intent.periodInvoiceId(), UpgradeQuoteJson.toJson(intent.quote()),
                intent.completedAt() == null ? null : intent.completedAt().atOffset(ZoneOffset.UTC),
                zero ? null : tax.rate().numerator(), zero ? null : tax.rate().denominator(),
                zero ? null : tax.description());
    }

    @Override
    public Optional<UpgradeIntent> findByReference(String paymentReference) {
        var found = jdbc.query("""
                SELECT payment_reference, subscription_id, account_id, new_plan_id, new_offer_version,
                       period_invoice_id, quote::text, completed_at, tax_rate_numerator, tax_rate_denominator,
                       tax_description
                FROM invoicing.upgrade_intent
                WHERE payment_reference = ?
                """, this::mapRow, paymentReference);
        return found.stream().findFirst();
    }

    @Override
    public Optional<UpgradeIntent> findOpenForPeriodInvoice(UUID periodInvoiceId) {
        var found = jdbc.query("""
                SELECT payment_reference, subscription_id, account_id, new_plan_id, new_offer_version,
                       period_invoice_id, quote::text, completed_at, tax_rate_numerator, tax_rate_denominator,
                       tax_description
                FROM invoicing.upgrade_intent
                WHERE period_invoice_id = ? AND completed_at IS NULL
                ORDER BY payment_reference
                """, this::mapRow, periodInvoiceId);
        return found.stream().findFirst();
    }

    @Override
    public void markCompleted(String paymentReference, Instant completedAt) {
        jdbc.update("""
                UPDATE invoicing.upgrade_intent SET completed_at = ?
                WHERE payment_reference = ? AND completed_at IS NULL
                """, completedAt.atOffset(ZoneOffset.UTC), paymentReference);
    }

    private UpgradeIntent mapRow(ResultSet rs, int rowNum) throws SQLException {
        Long taxNumerator = rs.getObject("tax_rate_numerator", Long.class);
        Long taxDenominator = rs.getObject("tax_rate_denominator", Long.class);
        String taxDescription = rs.getString("tax_description");
        TaxQuote tax = taxDescription == null
                ? TaxQuote.zero()
                : new TaxQuote(Ratio.of(taxNumerator, taxDenominator), taxDescription);
        UpgradeIntent intent = UpgradeIntent.place(rs.getString("payment_reference"),
                rs.getObject("subscription_id", UUID.class), rs.getObject("account_id", UUID.class),
                rs.getString("new_plan_id"), rs.getInt("new_offer_version"),
                rs.getObject("period_invoice_id", UUID.class), UpgradeQuoteJson.fromJson(rs.getString("quote")),
                tax);
        OffsetDateTime completed = rs.getObject("completed_at", OffsetDateTime.class);
        return completed == null ? intent : intent.completed(completed.toInstant());
    }
}
