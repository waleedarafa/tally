package com.tally.invoicing;

import com.tally.shared.Money;

/** One line of an invoice, as other modules see it: the amounts of a PriceQuote's line (I3). */
public record InvoiceLineView(String description, Money amount) {
}
