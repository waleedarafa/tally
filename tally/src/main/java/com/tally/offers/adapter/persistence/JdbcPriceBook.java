package com.tally.offers.adapter.persistence;

import com.tally.offers.BillingPeriod;
import com.tally.offers.domain.PriceBook;
import com.tally.shared.Money;
import java.util.Currency;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPriceBook implements PriceBook {

    private final JdbcTemplate jdbc;

    JdbcPriceBook(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Money> price(String planId, BillingPeriod period, Currency currency) {
        return jdbc.query("SELECT amount_minor FROM offers.plan_price"
                                + " WHERE plan_id = ? AND billing_period = ? AND currency = ?",
                        (rs, n) -> new Money(rs.getLong("amount_minor"), currency),
                        planId, period.name(), currency.getCurrencyCode())
                .stream().findFirst();
    }
}
