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
| `accessfeed` | Access feed: billing's side of the access facts (ADR-033) | — | ports and adapters | — |
| Access, `access/` (`com.tallyaccess`) | Access: a separate deployable since Slice 2 (ADR-001), with its own database | supporting | plain layers (web, application, persistence, log); one translator per partner later | Product |
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
accessfeed: shared, platform, subscription, account    # events only, never their APIs (ADR-033)
```

Access is not in this graph: it is a second deployable (ADR-001), in `access/`, base package `com.tallyaccess`, with its own database. It imports no billing code; billing and Access share only the files in `contracts/access-facts/` (ADR-034, F7). The in-process `access` module of Slice 1 left in Slice 2 PR-9, and V36 dropped its schema; the pinned graph is this one.

Named interfaces: `account` exposes a read API to every module that lists it, and `AccountStanding.set` to `collections` only (F3).
`support` joins in a later slice: `support: shared, account, invoicing, collections`.

**Context map** (Design Record Step 6, relationships table, drawn as one picture). Upstream at the top; each label is the pattern on that relationship. Code dependencies above point the other way: downstream depends on upstream.

```
Layer 1   Account ──CF──► every context          Payment ◄──ACL── Card provider
             │ OHS: set standing (Collections only)  │ OHS+PL: collect by reference
Layer 2   Offers & Pricing                            │
             │ OHS+PL: PriceQuote ──► Subscription and Invoicing
Layer 3   Subscription                                │
             │ PL: started, plan changed, period renewed, cancelled ──► Access, Invoicing
             │ OHS: cancel an account's subscriptions ◄── Collections
Layer 4   Access ◄──ACL── Telecom partners       Invoicing ◄──ACL── Tax service
                                                      │ PL+OHS: issued, payment failed, paid; request payment again
Layer 5                                          Collections
Layer 6   Support view ◄──CF── published events of Invoicing, Access, Collections, Account

Money: shared kernel (SK), used by every context. Email service and ledger: CF, used by Invoicing and Collections.
```

What the picture shows: one hub (Account, conformed to by every context), no cycles (the first draft had one, Step 6 "Cycle found and fixed"), and every outside system behind an anti-corruption layer.

## 4. Public APIs and events

| Module | Public API | Events |
|---|---|---|
| `offers` | `Pricing.quote(QuoteRequest) → PriceQuote`; `Offers.currentVersion(offerId, accountId)` | `OfferVersionPublished` |
| `subscription` | `SubscriptionApi.subscribe(accountId, offerId)` (the design's `Subscriptions.start`), `changePlan`, `cancel(id, requestedAt)`, `cancelForWriteOff` | `SubscriptionChanged` with types: started, plan changed, period renewed, renewal reversed, cancelled. Since Slice 1R PR-5 it carries the offer version and the current period (number, start, end) |
| `invoicing` | `InvoicingApi.requestPaymentAgain(invoiceId, retry)` (the retry number makes a repeated call harmless), `invoicesFor(accountId)` | `InvoiceIssued`, `InvoicePaymentDeclined`, `InvoicePaid`, `CreditNoteIssued` |
| `payment` | `PaymentApi.requestCollection(reference, accountId, amount, paymentToken)` (the design's `Payments.collect`), `collectAgain(reference, retry)` (Collections' retry of a declined reference, via Invoicing; looks the reference up first, ADR-026), `status(reference)` | `PaymentSucceeded`, `PaymentFailed` with reason declined; technical failures publish nothing |
| `account` | `Accounts.get(id)`; `AccountStanding.set(id, standing, cause)` | `StandingChanged`: event ID, time, account ID, sequence (the account's version), standing. Published in `AccountStanding.set`'s transaction, only when the standing changes; no cause text |
| `accessfeed` | none | none in process. Sends `AccessFacts` to the event log (ADR-033) |
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

**AccessFacts, version 1** (ADR-012, ADR-033, ADR-034): the current access facts of one account, on the event log's topic `access-facts`. Key: the account ID. The schema is `contracts/access-facts/v1/schema.json`.

```json
{
  "schemaVersion": 1,
  "accountId": "0b6f…",
  "sequence": 7,
  "producedAt": "2026-11-13T06:00:00Z",
  "standing": "GOOD",
  "sources": [
    { "kind": "SUBSCRIPTION", "id": "5c1e…", "state": "ACTIVE" }
  ]
}
```

- `sequence` counts from 1 for each account, and goes up by one only when the facts change: a repeated event, or a `PERIOD_RENEWED`, changes nothing and sends nothing.
- `standing`: GOOD, OVERDUE or BLOCKED. `kind`: SUBSCRIPTION only. `state`: ACTIVE only. Later slices add values (add only, ADR-016).
- IDs, enums, numbers and times only: no free text, so no personal data (ADR-006, D8, F15).
- For one account, one send at a time, each carrying the facts current when it starts; Access applies a message only if its sequence is higher.
- Each send is a transaction of its own, committed only once the broker acknowledges it; Access reads committed messages only (`read_committed`), so a send that was not acknowledged and reached the log later is never seen (ADR-033, spike S-4).
- Access keeps its position in the log in its own database, with the facts, in one transaction; an emptied database reads the log from the start with the old consumer group (ADR-035).

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
| SENT | older than the call timeout | UNKNOWN (a REQUESTED attempt was never sent; ADR-026) |
| UNKNOWN | resolver (ADR-026): a charge or decline found by reference, or the outcome of a repeat with the attempt's key while the key is kept | SUCCEEDED / DECLINED; otherwise stays UNKNOWN (never a new attempt) |

The lookup by reference shows every attempt's charges, so it decides only for the **latest** attempt of a reference (ADR-026, clarified after AT-16 in Slice 1R): an earlier UNKNOWN attempt learns its outcome only by a repeat with its own key, and a DECLINED attempt stops being re-checked once a later attempt exists.

Payment allows a new attempt only while no attempt is REQUESTED, SENT or UNKNOWN, and never after SUCCEEDED.

**Subscription:** TRIAL → ACTIVE (trial ends) · TRIAL or ACTIVE → CANCELLING (cancel) · ACTIVE → ACTIVE (`renew(n)` for the current period only) · ACTIVE → ENDED (late cancel requested before period end; emits renewal reversed) · CANCELLING → ENDED (period ends) · any live state → ENDED (write-off) · ENDED refuses everything.

As Slice 3 builds it (proposed ADR-038): only ACTIVE and ENDED.

| From | Event | To |
|---|---|---|
| ACTIVE | `renew(n)` for the current period, account not BLOCKED | ACTIVE, period n (`PERIOD_RENEWED`) |
| ACTIVE | `renew(n)` while the account is BLOCKED | ACTIVE, unchanged; the renewal waits and runs once the standing is GOOD (I6) |
| ACTIVE | end, reason `WRITE_OFF` or `FIRST_PAYMENT_DECLINED` (025) | ENDED (`ENDED`) |
| ENDED | anything, `renew` and end included | ENDED, unchanged |

One live subscription per account; an account whose subscription ended may sign up again. TRIAL, CANCELLING and a cancel by the customer come with the billing lifecycle slice.

**Collections case (one per account; proposed ADR-037):** the case covers every unpaid invoice of the account. Days count from the case's first decline. Only a decline opens a case (ADR-013); after that no payment outcome moves it forward: each timer looks only at "is a debt still open", and only `InvoicePaid` moves it back. The standing follows the case in the same transaction.

| From | Event | To | Standing |
|---|---|---|---|
| (none) | a renewal's payment declined | OPEN; retry 1 due on day 3 | GOOD |
| (none) | the first invoice's payment declined (025; found through `InvoicingApi`) | no case; the subscription ends | GOOD |
| OPEN, OVERDUE, BLOCKED | another invoice declined | the same state, the invoice added | unchanged |
| OPEN | day 3 | OPEN; retry 1 asked; retry 2 due on day 7 | GOOD |
| OPEN | day 7 | OPEN; retry 2 asked; OVERDUE due one hour later | GOOD |
| OPEN | day 7 plus one hour, a debt open | OVERDUE | OVERDUE |
| OVERDUE | day 10, a debt open | BLOCKED | BLOCKED |
| BLOCKED | day 30, a debt open | WRITTEN_OFF; the subscription ends (`WRITE_OFF`) | BLOCKED |
| OPEN, OVERDUE, BLOCKED | every invoice of the case paid (a retry or "pay now") | CLOSED | GOOD |
| WRITTEN_OFF | every invoice of the case paid ("pay now") | CLOSED; the subscription stays ended | GOOD |

Each timer is a claimed row on Collections' queue, with a business unique constraint for the step. "Pay now" asks again in any state with a debt open; its decline changes no state. *(Slice 1R built OPEN, the day-3 retry and close for one invoice; Slice 3 builds the rest.)*

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
| `POST /v1/accounts/{accountId}/collections/payment-requests` | 202: caseId, retry, invoiceIds; 404: no debt open | "pay now" (Slice 3, decision 2): every unpaid invoice of the case; every request of a case, scheduled or "pay now", takes the next retry number at the moment it is asked; `Idempotency-Key` required |
| `GET /v1/access/{subscriberId}` | canWatch, reason, asOf, sequence | served by Access |

**`GET /v1/access/{subscriberId}`**, served by Access (ADR-001), from its own copy. In Slice 2 the subscriber is the account (ADR-033). Example: `{ "subscriberId": "0b6f…", "canWatch": true, "reason": "ACTIVE_SOURCE", "asOf": "2026-11-13T06:00:00Z", "sequence": 7 }`.

| Access's copy says | canWatch | reason |
|---|---|---|
| At least one active source, standing not BLOCKED | true | `ACTIVE_SOURCE` |
| Standing BLOCKED | false | `ACCOUNT_BLOCKED` |
| No active source | false | `NO_ACTIVE_SOURCE` |
| No row for this ID | false | `NOT_KNOWN` (the app shows "activating", ADR-014) |

`asOf` is the `producedAt` of the facts behind the answer, and `sequence` their sequence; for `NOT_KNOWN` both are null. A standing or a source state Access does not know never takes access away: an unknown standing is not BLOCKED, and an unknown state counts as active (ADR-033).

**Not ready** (ADR-036): while an instance's copy is behind the end of the log it saw at start, it answers 503 with no body, and its readiness probe (`/actuator/health/readiness`, health indicator `copyReady`) says no. A copy that once reached that end is marked complete in Access's database, so an instance that finds the mark answers at once, also while the log is down; an emptied database has no mark, so a rebuild waits for the catch-up. A failure of the log or of Access's database never breaks liveness; only a reading thread that ends does. Slice 2 replaced `GET /v1/accounts/{id}/access`, served by billing, with this endpoint; billing no longer answers access.

Errors: `application/problem+json` (RFC 9457). Types: `offer-not-available`, `already-subscribed`, `account-blocked`, `idempotency-key-reused`, `request-in-progress` (409, with `Retry-After`: the same request is still being handled; added in Slice 1R PR-9b), `concurrent-change` (409, with `Retry-After`: the request met a change made at the same time, such as a standing set during a sign-up; nothing was changed, the key is not used up, and the same request is sent again with the same key; added in Slice 3 PR-4, WAR-57).
An `Idempotency-Key` must be a UUID; any other value is refused with 400 and not stored (Slice 1R PR-10, from the #24 review: loosening this later is additive, tightening it would break clients). Idempotency keys are claimed before acting; the response is stored in the same transaction as the change, and both are kept for 24 hours. Keys are scoped by account until callers are authenticated. Versions live in the path; changes are additive; a break is `/v2`.
