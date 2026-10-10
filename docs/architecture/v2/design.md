# Tally second design: what the code must match

A summary of the Design Record, Steps 6, 7, 10, 12 and 16. Section numbers here are referenced by `docs/briefs/slice-1r.md`.

## 1. Contexts

| Module | Context | Type | Inside layout | Business owner |
|---|---|---|---|---|
| `offers` | Offers and Pricing | core | ports and adapters | Marketing |
| `subscription` | Subscription | supporting | ports and adapters | Product |
| `invoicing` | Invoicing | generic | ports and adapters | Finance |
| `payment` | Payment | generic | ports and adapters | Engineering |
| `collections` | Collections | supporting | closed layers | Finance |
| `account` | Account | supporting | closed layers | Finance |
| `access` | Access (separate deployable from Slice 2) | supporting | layers plus one translator per partner | Product |
| `support` | Support view (read model; later slice) | — | closed layers | Customer support |
| `shared` | Shared kernel: `Money`, `Ratio`, `DomainEvent` | — | — | — |
| `platform` | Queues, jobs, listener executor (kept from the first run) | — | — | Engineering |

## 2. Invariants, and the aggregate that holds each

| Rule | Held by |
|---|---|
| O1 A new kind of offer is published only after finance approves the kind | `Offer`, reading `OfferKind` (approval never withdrawn) |
| O2 A published offer version never changes; a change makes a new version | `Offer` |
| O3 The same subscription, period and offer version always give the same PriceQuote | `Offer` terms plus pure `PricingRule`s |
| O4 Proration by days | `ProrationPolicy` |
| O5 On upgrade, a promotion does not carry over unless the offer says so | `Offer` terms |
| S1 One current plan and one current period | `Subscription` |
| S2 Periods never overlap or leave gaps (by construction: the next starts where the last ended) | `Subscription` |
| S3 Upgrade at once; downgrade at period end | `Subscription` |
| S4 Cancel at period end; no refund for the current period | `Subscription` |
| S5 Periods follow the account's billing day | `Subscription` |
| I1 An issued invoice never changes; corrections are credit notes | `Invoice`, `CreditNote` |
| I2 At most one invoice per subscription per period | `Invoice` ID = subscription + period number |
| I3 Invoice lines are amounts from a PriceQuote | `Invoice` |
| I6 No new invoice while blocked | **a policy, not an invariant**: checked at issue, corrected by credit note |
| P1 A reference is charged at most once | `Payment`, ID = reference |
| P2 No new attempt while one is requested, sent or unknown | `Payment` |
| P3 Only a provider token, never a card number | `Payment` |

Invoice numbers: gap-free per country, taken from a counter row in the same transaction as the invoice. This is the one stated exception to "one transaction changes one aggregate" *(gap-free requirement assumed; finance confirms)*.

## 3. Module dependency graph (target)

One line per module: `module: allowed dependencies`. Code dependencies point from downstream to upstream.

```
shared:
platform: shared
account: shared, platform
payment: shared, platform
offers: shared, platform, account
subscription: shared, platform, account, offers
invoicing: shared, platform, account, offers, subscription, payment
collections: shared, platform, account, subscription, invoicing
access: shared, platform, subscription, account    # TEMPORARY until Slice 2: events only, no API calls
```

Named interfaces: `account` exposes a read API to every module that lists it, and `AccountStanding.set` to `collections` only (F3).
`support` joins in a later slice: `support: shared, account, invoicing, collections`.

## 4. Public APIs and events

| Module | Public API | Events |
|---|---|---|
| `offers` | `Pricing.quote(QuoteRequest) → PriceQuote`; `Offers.isOffered(offerId, accountId)`, `Offers.currentVersion(offerId, accountId)` | `OfferVersionPublished` |
| `subscription` | `SubscriptionApi.subscribe(accountId, offerId)` (the design's `Subscriptions.start`), `changePlan`, `cancel(id, requestedAt)`, `cancelForWriteOff` | `SubscriptionChanged` with types: started, plan changed, period renewed, renewal reversed, cancelled. Since Slice 1R PR-5 it carries the offer version and the current period (number, start, end) |
| `invoicing` | `Invoicing.requestPaymentAgain(invoiceId)`, `invoicesFor(accountId)` | `InvoiceIssued`, `InvoicePaymentDeclined`, `InvoicePaid`, `CreditNoteIssued` |
| `payment` | `PaymentApi.requestCollection(reference, accountId, amount, paymentToken)` (the design's `Payments.collect`), `collectAgain(reference, retry)` (Collections' retry of a declined reference, via Invoicing; looks the reference up first, proposed ADR-026), `status(reference)` | `PaymentSucceeded`, `PaymentFailed` with reason declined; technical failures publish nothing |
| `account` | `Accounts.get(id)`; `AccountStanding.set(id, standing, cause)` | `StandingChanged` |
| `collections` | `Collections.caseFor(accountId)` | none |

```java
public record PriceQuote(UUID subscriptionId, String offerId, int offerVersion,
                         Period period, List<PriceLine> lines, Money total, QuoteReason reason) {}
public record QuoteRequest(UUID subscriptionId, UUID accountId, String offerId,
                           int offerVersion, Period period) {}               // the version is required
public record Period(int number, LocalDate start, LocalDate end) {}       // [start, end)
public record PriceLine(String description, Money amount) {}            // a credit is negative
public record QuoteReason(String offerId, int offerVersion, String kind, Map<String, Boolean> flags) {}
```
*(Real names since Slice 1R PR-4: identifiers are `UUID` and `String`, not value types.)*

**Offer version (Slice 1R PR-5, clarifies O3):** a subscription keeps the offer version it signed up to; `QuoteRequest.offerVersion` is required and `Pricing.quote` prices that version. A new version applies to new sign-ups only; moving existing subscribers is a later, explicit, dated change.

**Envelope:** every event carries event ID, occurrence time, account ID and the aggregate version as a sequence number.
**Evolution:** add fields only; never rename or move an event class; every shared enum has `UNKNOWN`, which unknown input maps to.

## 5. State machines

Each lives as an enum and a transition table inside its aggregate. One table-driven test tries every state with every event.

**Payment attempt**

| From | Event | To |
|---|---|---|
| REQUESTED | call starts | SENT |
| SENT | provider charged | SUCCEEDED (final) |
| SENT | provider declined | DECLINED (final; Invoicing then Collections) |
| SENT | provider error or refused before charging | TECHNICAL_FAILURE (retry after back-off; no event) |
| SENT | timeout | UNKNOWN |
| SENT | older than the call timeout | UNKNOWN (a REQUESTED attempt was never sent; proposed ADR-026) |
| UNKNOWN | resolver (proposed ADR-026): a charge or decline found by reference, or the outcome of a repeat with the attempt's key while the key is kept | SUCCEEDED / DECLINED; otherwise stays UNKNOWN (never a new attempt) |

Payment allows a new attempt only while no attempt is REQUESTED, SENT or UNKNOWN, and never after SUCCEEDED.

**Subscription:** TRIAL → ACTIVE (trial ends) · TRIAL or ACTIVE → CANCELLING (cancel) · ACTIVE → ACTIVE (`renew(n)` for the current period only) · ACTIVE → ENDED (late cancel requested before period end; emits renewal reversed) · CANCELLING → ENDED (period ends) · any live state → ENDED (write-off) · ENDED refuses everything.

**Collections case (one per account):** OPEN on a declined payment → day 3 and day 7 retries → OVERDUE (day 7) → BLOCKED (day 10) → WRITTEN_OFF (day 30). `InvoicePaid` closes it from any open state and restores standing. Timers are claimed rows. *(Slice 1R builds OPEN, the day-3 retry and close only.)*

## 6. Money

- `Money(long minorUnits, Currency currency)`: minor units from `Currency.getDefaultFractionDigits()` (AED, SAR, EGP: 2; KWD: 3). `Money.of(BigDecimal, code)` converts a decimal amount exactly and never rounds.
- `Ratio(long numerator, long denominator)`: exact.
- Rounding happens only in `Money.times(Ratio)`: half up, to the minor unit *(finance confirms per country)*.
- Totals are sums of rounded lines, never rounded again. `allocate(int...)` uses largest remainder.

Golden examples:

| Case | Expected |
|---|---|
| 2500 fils × 17/30 | 1417 fils |
| 5% of 1417 fils | 71 fils |
| 1417 + 71 | 1488 fils |
| allocate 1000 fils over weights 1, 1, 1 | 334, 333, 333 |
| 1.000 KWD × 1/3 | 0.333 KWD (333 minor units) |

## 7. Ports in the ports-and-adapters modules

Clock (all with dates), `CardProvider` (payment), `TaxRates` (invoicing), `InvoiceNumbers` (invoicing), one repository per aggregate, events out. Other modules are called from the application layer, never from the domain: `Invoice.issue` takes amounts, never an offer.

## 8. REST contract, v1

| Request | Answer | Notes |
|---|---|---|
| `POST /v1/subscriptions` | 201: subscriptionId, status, `accessWithinSeconds: 5` | `Idempotency-Key` required |
| `POST /v1/subscriptions/{id}/plan-changes` | 202: effective date | later slice |
| `POST /v1/subscriptions/{id}/cancellation` | 202: endsAt | `requestedAt` set by Tally on arrival |
| `GET /v1/subscriptions/{id}` | status, plan, current period | |
| `GET /v1/accounts/{id}/invoices` | invoices with amounts | |
| `GET /v1/access/{subscriberId}` | canWatch, reason, asOf, sequence | served by Access |

Errors: `application/problem+json` (RFC 9457). Types: `offer-not-available`, `already-subscribed`, `account-blocked`, `idempotency-key-reused`.
Idempotency keys are stored before acting, with the response, for 24 hours. Versions live in the path; changes are additive; a break is `/v2`.
