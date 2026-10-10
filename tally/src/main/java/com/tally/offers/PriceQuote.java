package com.tally.offers;

import com.tally.shared.Money;

public record PriceQuote(String planId, BillingPeriod period, Money price) {
}
