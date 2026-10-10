package com.tally.offers.domain;

import com.tally.offers.BillingPeriod;
import com.tally.shared.Money;
import java.util.Currency;
import java.util.Optional;

/** Port: the plans' list prices. */
public interface PriceBook {

    Optional<Money> price(String planId, BillingPeriod period, Currency currency);
}
