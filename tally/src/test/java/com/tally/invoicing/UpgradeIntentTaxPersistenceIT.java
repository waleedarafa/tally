package com.tally.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tally.AbstractIntegrationTest;
import com.tally.invoicing.domain.Invoice;
import com.tally.invoicing.domain.InvoiceLine;
import com.tally.invoicing.domain.Invoices;
import com.tally.invoicing.domain.TaxQuote;
import com.tally.invoicing.domain.UpgradeIntent;
import com.tally.invoicing.domain.UpgradeIntents;
import com.tally.offers.Period;
import com.tally.offers.PriceLine;
import com.tally.offers.PriceQuote;
import com.tally.offers.QuoteReason;
import com.tally.shared.Money;
import com.tally.shared.Ratio;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Saves and loads the tax on an upgrade intent: rate and description survive the round trip (ADR-051). */
class UpgradeIntentTaxPersistenceIT extends AbstractIntegrationTest {

    static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");

    @Autowired
    UpgradeIntents intents;

    @Autowired
    Invoices invoices;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcTemplate jdbc;

    /** Persists one real invoice, priced at 1200 AED, and returns it. */
    private Invoice persistInvoice() {
        Invoice invoice = Invoice.issue(UUID.randomUUID(), UUID.randomUUID(), "BASIC", 1,
                List.of(InvoiceLine.of("Price", Money.of(1200, "AED"))),
                new PricingReason("BASIC", 1, "fixed-price-phase", Map.of()),
                LocalDate.parse("2026-01-15"), LocalDate.parse("2026-02-15"), NOW, NOW);
        transactions.executeWithoutResult(tx -> assertThat(invoices.addIfAbsent(invoice)).isTrue());
        return invoice;
    }

    private PriceQuote goldenQuote(UUID subscriptionId) {
        return new PriceQuote(subscriptionId, "PRO", 2,
                new Period(1, LocalDate.parse("2026-01-15"), LocalDate.parse("2026-02-15")),
                List.of(new PriceLine("Unused Basic", Money.of(-600, "AED")),
                        new PriceLine("Premium remaining", Money.of(1000, "AED"))),
                Money.of(400, "AED"), new QuoteReason("PRO", 2, "proration", Map.of()));
    }

    private String reference() {
        return "INV-" + UUID.randomUUID();
    }

    private UpgradeIntent place(Invoice invoice, String reference, TaxQuote tax) {
        return UpgradeIntent.place(reference, invoice.subscriptionId(), invoice.accountId(), "PRO", 2,
                invoice.id(), goldenQuote(invoice.subscriptionId()), tax);
    }

    @Test
    void aTaxRateSurvivesTheRoundTrip() {
        Invoice invoice = persistInvoice();
        TaxQuote tax = new TaxQuote(Ratio.of(5, 100), "VAT (5%)");
        String reference = reference();
        UpgradeIntent intent = place(invoice, reference, tax);

        transactions.executeWithoutResult(tx -> intents.place(intent));

        UpgradeIntent loaded = intents.forReference(reference).orElseThrow();
        assertThat(loaded.tax()).isEqualTo(tax);
        assertThat(loaded.charge()).isEqualTo(Money.of(420, "AED"));
        UpgradeIntent open = intents.openFor(invoice.id()).orElseThrow();
        assertThat(open.tax()).isEqualTo(tax);
    }

    @Test
    void anIntentWithoutTaxReadsAsZero() {
        Invoice invoice = persistInvoice();
        String reference = reference();
        UpgradeIntent intent = UpgradeIntent.place(reference, invoice.subscriptionId(), invoice.accountId(),
                "PRO", 2, invoice.id(), goldenQuote(invoice.subscriptionId()));

        transactions.executeWithoutResult(tx -> intents.place(intent));

        UpgradeIntent loaded = intents.forReference(reference).orElseThrow();
        assertThat(loaded.tax()).isEqualTo(TaxQuote.zero());
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT tax_rate_numerator, tax_rate_denominator, tax_description FROM invoicing.upgrade_intent WHERE payment_reference = ?",
                reference);
        assertThat(row.get("tax_rate_numerator")).isNull();
        assertThat(row.get("tax_rate_denominator")).isNull();
        assertThat(row.get("tax_description")).isNull();
    }

    @Test
    void completingKeepsTheTax() {
        Invoice invoice = persistInvoice();
        TaxQuote tax = new TaxQuote(Ratio.of(5, 100), "VAT (5%)");
        String reference = reference();
        UpgradeIntent intent = place(invoice, reference, tax);

        transactions.executeWithoutResult(tx -> {
            intents.place(intent);
            intents.markCompleted(reference, Instant.parse("2026-02-01T10:00:00Z"));
        });

        UpgradeIntent loaded = intents.forReference(reference).orElseThrow();
        assertThat(loaded.completed()).isTrue();
        assertThat(loaded.tax()).isEqualTo(tax);
    }

    @Test
    void theDatabaseRefusesAHalfFilledTax() {
        Invoice invoice = persistInvoice();
        String reference = reference();
        UpgradeIntent intent = place(invoice, reference,
                new TaxQuote(Ratio.of(5, 100), "VAT (5%)"));
        transactions.executeWithoutResult(tx -> intents.place(intent));

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE invoicing.upgrade_intent SET tax_rate_numerator = 5, tax_rate_denominator = NULL, tax_description = NULL WHERE payment_reference = ?",
                reference))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
