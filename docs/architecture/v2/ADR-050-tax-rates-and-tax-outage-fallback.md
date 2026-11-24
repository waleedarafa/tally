# ADR-050. Tax behind TaxRates and wait-and-retry when tax is down

**Status:** Accepted, 25 November 2026 (WAR-67; taken by delegation).

## Context

Tally bills households in jurisdictions with different VAT rules: the UAE (5% VAT) and Saudi Arabia (15% VAT, driver D12), plus tax-exempt markets such as the US test default (0%). Tax is bought, not built (FR-016), behind the `TaxRates` port in `com.tally.invoicing.domain` (`design.md` §7, D2-11).

Two design questions arise for WAR-67:
1. How `Invoice` represents tax quoting in its lifecycle (D2-03) and keeps external tax wire models out of the Invoicing domain (A-11).
2. What Invoicing does when the external tax service is unavailable when an invoice is due to be billed (D2-04).

The Design Record snapshot (`design-record.md`) is not edited.

## Decision

1. **Port and anti-corruption layer (FR-016, D2-11, A-11).**
   - `TaxRates` lives in `com.tally.invoicing.domain` alongside `TaxRequest`, `TaxQuote`, and `TaxUnavailableException`. It takes jurisdiction country code, currency, pre-tax lines/amount, and the tax point date — the invoice date in the account's local time zone (H8: `LocalDate.ofInstant(billedFor, account.timeZone())`).
   - `com.tally.invoicing.adapter.tax` (`AclTaxRates`, configured via `TaxProperties` under `tally.tax.*`) translates external tax wire DTOs (`jurisdiction`, `ratePercent`, `taxCode`, `status`) into `TaxQuote(Ratio, description)` or `TaxUnavailableException`. Adding a new market such as Saudi Arabia (SAR, 15% VAT, D12) requires only tax settings and offer price lists, with zero code changes in `offers` or `invoicing.domain`.

2. **Invoice states with tax (D2-03).**
   - `InvoiceStatus` adds `DRAFT` and `TAX_QUOTED` before `PAYMENT_PENDING` (or immediate `PAID` when the gross total is zero), preserving `UNKNOWN` as `@JsonEnumDefaultValue`.
   - `Invoice.draft(...)` creates an unissued invoice in `DRAFT` (`issuedAt = null`, `version = 0`) holding the pre-tax lines.
   - `quoteTax(TaxQuote)` transitions `DRAFT -> TAX_QUOTED`, calculates tax per pre-tax line using `Money.times(Ratio)` rounded half-up (ADR-017), sums those rounded tax amounts, and when tax is positive appends one tax line and updates `amountMinor`. When tax is zero, no tax line is appended.
   - `issue(Instant now)` transitions `TAX_QUOTED -> PAYMENT_PENDING` (or `PAID` when gross amount is zero) and sets `issuedAt = now`. Only then is `InvoiceIssued` published (and `InvoicePaid` when zero). Unissued `DRAFT`/`TAX_QUOTED` rows have `issuedAt = null`, so `InvoicedVersusCollected` and payment collection ignore them.
   - Scope note: mid-period upgrade adjustments (`UpgradeService`, ADR-041) charge upfront and settle immediately; tax quoting on adjustments is out of scope for WAR-67's first build.

3. **Wait and retry when tax is down — never silently invoice without tax (D2-04).**
   - Calls to `TaxRates` always run with no database transaction open.
   - On the normal path when `TaxRates` responds, `InvoiceIssuer` runs `draft -> quoteTax -> issue` in memory and inserts the issued invoice once at `ISSUED_VERSION`, avoiding transient `DRAFT` rows.
   - When `TaxRates` throws `TaxUnavailableException`, `InvoiceIssuer` persists the invoice in `DRAFT` (`issuedAt = null`, idempotent on `Invoice.id`), publishes no `InvoiceIssued`, sends nothing to Payment, and enqueues the invoice on a dedicated money queue `invoicing.tax_quote_queue` (duplicate guard `invoicing.invoice_one_period_per_subscription_and_period`).
   - `TaxQuoteWorker` retries outside any transaction: if tax is still unavailable, it returns `Outcome.retryAt(now + retryDelay)` (not `FAILED`, so temporary outages do not dead-letter items, while queue age/lateness alerts still fire if an outage lasts); once tax responds, it transitions `DRAFT -> TAX_QUOTED -> PAYMENT_PENDING` (or `PAID` if zero) in a short transaction and publishes `InvoiceIssued`.

## Consequences

- No household is ever invoiced or charged silently without tax during a tax-service outage.
- Saudi Arabia (D12) and UAE (5% VAT) work purely through `tally.tax.*` settings and offer prices.
- `InvoicedVersusCollected` continues checking only issued invoices (`issuedAt` non-null).
- An outage delays invoicing for the duration of the outage; prolonged outages trigger queue-age alerts (`BillingBehind`).

## Rejected

- **Silently invoicing without tax (0%):** under-bills customers and creates compliance and VAT liability.
- **Issuing with a cached/last-known rate and adjusting later with a credit note:** introduces tax-authority mismatches if rates or exemptions change and complicates accounting reconciliation.
