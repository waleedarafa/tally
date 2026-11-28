package com.tally.offers;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.AbstractIntegrationTest;
import com.tally.flags.domain.FlagCatalogue;
import com.tally.flags.domain.Flags;
import com.tally.support.TestAccounts;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Every defined flag's state is recorded on the quote reason, read from the stored catalogue (WAR-66 step 4,
 * ADR-010, ADR-048): the flags table is cleaned before and after every test.
 */
class QuoteFlagsIT extends AbstractIntegrationTest {

    static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    static final Period PERIOD = new Period(1, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"));

    @Autowired
    Pricing pricing;

    @Autowired
    TestAccounts testAccounts;

    @Autowired
    FlagCatalogue catalogue;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM flags.flag");
    }

    @AfterEach
    void cleanAfter() {
        jdbc.update("DELETE FROM flags.flag");
    }

    @Test
    void recordsEveryDefinedFlagInQuoteReasonAndAccountOverridesCountry() {
        UUID accountA = testAccounts.open(Currency.getInstance("AED"), ZoneId.of("Asia/Dubai"), "AE").id();
        UUID accountB = testAccounts.open(Currency.getInstance("AED"), ZoneId.of("Asia/Dubai"), "AE").id();
        UUID accountC = testAccounts.open(Currency.getInstance("AED"), ZoneId.of("Asia/Dubai"), "US").id();

        Flags flags = new Flags();
        flags.define("family-bundle", NOW);
        flags.assignCountry("family-bundle", "AE", true, NOW);
        flags.assignAccount("family-bundle", accountA, false, NOW);
        flags.define("canary-proration", NOW);
        flags.setDefault("canary-proration", true, NOW);
        catalogue.save(flags);

        Map<UUID, Map<String, Boolean>> expected = Map.of(
                accountA, Map.of("family-bundle", false, "canary-proration", true),
                accountB, Map.of("family-bundle", true, "canary-proration", true),
                accountC, Map.of("family-bundle", false, "canary-proration", true));

        for (UUID account : List.of(accountA, accountB, accountC)) {
            PriceQuote quote = pricing.quote(new QuoteRequest(UUID.randomUUID(), account, "BASIC", 1, PERIOD));
            assertThat(quote.reason().flags()).as("quote: " + account).isEqualTo(expected.get(account));

            PriceQuote change = pricing.quoteChange(new ChangeRequest(UUID.randomUUID(), account, "BASIC", 1,
                    "PREMIUM", 1, PERIOD, LocalDate.parse("2026-01-16")));
            assertThat(change.reason().flags()).as("quoteChange: " + account).isEqualTo(expected.get(account));
        }
    }
}
