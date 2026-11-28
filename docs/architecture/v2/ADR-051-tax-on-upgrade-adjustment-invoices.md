# ADR-051. Tax on upgrade adjustment invoices; the credit is tax-inclusive

**Status:** Accepted, 26 November 2026 (WAR-67 follow-up; taken by delegation).

## Context

ADR-041 charges a mid-period upgrade first and issues the documents after the charge succeeds: an adjustment invoice for the new plan's remaining days, and a credit note for the unused part of the plan being left, applied to that adjustment. ADR-040 and ADR-044 say which invoice each credit note corrects. ADR-050 put VAT on period invoices and marked adjustments out of scope. Today an adjustment is never taxed, so an account in the UAE is charged VAT on its period invoice and none on an upgrade, and a credit note refunds the period invoice's VAT-inclusive plan price without its VAT.

Two facts shape the design:

1. The charge is the saga's pivot (decision 005, ADR-041). Its amount must be known before the charge, so the tax must be quoted before the charge, not after.
2. `completeAfterCharge` runs in a transaction, and no call to `TaxRates` may run with a transaction open (ADR-050). Completion must therefore not ask for tax again.

## Decision

**Tax is quoted when the upgrade is requested, and the rate is remembered.** `UpgradeService.upgrade` asks `TaxRates` (outside any transaction) for the account's country, on the net of the quote's charge lines, with the tax point date the upgrade's effective date in the account's time zone (H8). The answer, a rate and a description, is stored on the upgrade intent (`invoicing.upgrade_intent`, additive columns, V59). Completion never asks again, so the amounts on the documents are the amounts that were charged.

**VAT is per line, half up (ADR-017), on both sides.**
- The adjustment invoice goes through the same states as any invoice (ADR-050): `DRAFT`, `quoteTax`, `issue`, then paid, because the charge has already succeeded. Its lines are the quote's charge lines plus one tax line (`VAT (5%)`) holding the sum of the per-line half-up VAT. A zero rate adds no tax line.
- **The credit is tax-inclusive.** The credit note reverses the VAT that the unused part of the old plan carried, at the same rate, per credit line, half up: its lines are the quote's credit lines (as positive amounts) plus one tax line with the sum of the per-line VAT. The period invoice (or the previous adjustment, ADR-044) holds VAT-inclusive amounts, so crediting the net alone would leave the household paying VAT on a plan it no longer has.
- Per-line rounding is applied to each credit line on its own, never to the whole credit, so the VAT on the credit can differ by one minor unit from a pro-rata share of the VAT on the original invoice. That is the same rule as ADR-050 and is accepted.

**The amount charged is the net of the two documents, VAT included:** `(charge lines + VAT on them) - (credit lines + VAT on them)`. For a quote with a charge of 1000 and a credit of 600 at 5%: adjustment 1000 + 50 = 1050, credit 600 + 30 = 630, charge 420. The charge is computed by one function on the intent, used both for the first request and for a retry of the same upgrade. A retry therefore charges the amount stored on the intent, with the stored rate, never a fresh quote (the same reference is always charged the same amount).

**When tax is unavailable the upgrade is refused; nothing is written.** `upgrade` throws `TaxUnavailableException` before the intent is saved and before Payment is called: no charge, no intent, no documents, the old plan stays (ADR-041), and the caller may ask again. ADR-050's wait-and-retry through `invoicing.tax_quote_queue` is not used for upgrades: it holds a persisted `DRAFT` invoice so a later worker can issue it, but an upgrade has nothing to persist before its charge succeeds, and it cannot be charged without a known amount. The principle of ADR-050 is kept: an upgrade is never charged, and never invoiced, without tax.

The `quote.total() <= 0` refusal (nothing to collect) is unchanged and tested on the net of tax. A zero rate (the US test default) gives exactly today's documents and amounts.

## Consequences

- An AE account that upgrades gets an adjustment with a VAT line and a credit note with a VAT line; the net charged includes VAT.
- `upgrade_intent` gains the tax rate and description. An intent saved before V59 has none, and reads as a zero rate, which gives its original amounts.
- A tax outage makes `upgrade` fail for the duration; the household sees an error and tries again. A long outage is visible through the tax-quote alert only for period invoices; upgrades leave nothing queued.
- A credit note is still capped by what is available on the invoice it corrects (`CreditNote`), so it can never credit more than was invoiced. An invoice issued before tax existed (none outside tests) has no VAT line; a tax-inclusive credit against it is capped by that rule, not refused.
- The Design Record snapshot, ADR-040, ADR-041, ADR-044 and ADR-050 are not edited. This record resolves ADR-050's scope note.

## Rejected

- **Credit the net amount only:** the household would be charged VAT on a plan it left, and the period invoice's VAT would never be reversed.
- **Charge first, add VAT later with a second document:** the charge is the pivot and must be the final amount (ADR-041, decision 005).
- **Queue the upgrade for a later retry when tax is down:** nothing is persisted before the charge, and a queued upgrade could be charged at a later date at a different proration.
- **Ask for tax again at completion:** completion is transactional (no external call with a transaction open), and a rate that changed between the charge and the documents would make the documents differ from the charge.

## Drivers

ADR-017; ADR-040; ADR-041; ADR-044; ADR-050; D12; H8; D2-03.

## Revisit when

Finance wants the credit's VAT taken pro rata from the VAT on the original invoice instead of per credit line, or the upgrade must survive a tax outage by waiting.
