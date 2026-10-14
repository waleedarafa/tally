# Glossary

The words Tally uses, in one place, and where each was decided. Every meaning comes from the Design Record or a decision record; this file only gathers them. The same word may mean different things in two contexts: each context has its own section, and the boundary keeps the meanings apart.

A new term enters this file in the pull request that introduces it in code. If code and this file disagree, the Design Record decides, and one of them is fixed.

## Names that cross every context

From Design Record Step 6 ("Account decision") and Step 3 (boundary signals).

| Word | Meaning | Not to be confused with |
|---|---|---|
| Account | Who pays | Subscriber, customer |
| Subscriber | The person who watches | Account |
| Customer | Marketing's word for a segment; not used in code | Account, subscriber |
| Suspended | Viewing stopped; billing facts unchanged. The subscription continues and the open invoice is still owed (H3) | Blocked (a standing), cancelled (a subscription state) |

The same money has four names on its way through Tally: offer → amount for the period (PriceQuote) → invoice line → payment (Step 3).

## Offers and Pricing (`offers`)

From the Step 7 canvas and `design.md` §2 and §4.

| Word | Meaning |
|---|---|
| Offer | A sellable plan with its phases and terms |
| Offer version | A published offer, which never changes; a change makes a new version (O2). A subscription keeps the version it signed up to (Slice 1R) |
| Kind of offer | Discount, free trial, fixed-price phase, bundle price; anything else is a new kind, and needs finance's sign-off before publication (O1, H1) |
| Phase | A stretch of time with one price, such as "first month free" |
| PriceQuote | The answer to "what does this subscription owe for this period, and why": amount lines, currency, period and reason. The same subscription, period and version always give the same PriceQuote (O3) |
| Reason (QuoteReason) | Offer, offer version, kind and flags behind a PriceQuote; stored on the invoice so a charge can be explained (D7) |
| Proration | By days: unused days are credited, remaining days are charged at the new price (O4) |

## Subscription (`subscription`)

| Word | Meaning |
|---|---|
| Subscription | One plan bought by one account |
| Period | The stretch of time one invoice covers, `[start, end)`, numbered from 1. Periods never overlap or leave gaps (S2) |
| Renewal | Creating the next period; `renew(n)` creates period n once and changes nothing if it exists |
| Billing day | The day each period starts; set by the account's first subscription (S5) |
| Billing time | 00:00 on the billing day in the account's time zone, when a renewal falls due (H8, `BillingCalendar`) |
| Plan change | An upgrade or downgrade, with its effective date. Upgrade at once; downgrade at period end (S3) |
| Cancellation | An end date, always a period end; no refund for the current period (S4, H11) |
| States | TRIAL, ACTIVE, CANCELLING, ENDED; ENDED refuses everything (`design.md` §5; not built yet) |

## Access (`access`)

| Word | Meaning |
|---|---|
| Subscriber | The person who watches |
| Source of access | A subscription, a trial or a partner bundle. A subscriber can watch if at least one source is active |
| Suspended | Viewing stopped; billing facts unchanged |
| Activating | What the app shows while a new subscriber's access arrives; 99% within 5 seconds (ADR-014) |
| Access facts | The current facts per account that billing publishes to Access, IDs only (ADR-006, ADR-012) |

## Invoicing (`invoicing`)

| Word | Meaning |
|---|---|
| Invoice | A legal record of what an account owes for a period. Its ID is subscription + period number, so there is at most one per subscription per period (I2) |
| Line | One amount from a PriceQuote (I3) |
| Credit note | The only way to correct an issued invoice; an issued invoice never changes (I1) |
| Reference | The opaque label Payment collects against |
| Zero invoice | An invoice of zero, such as a free trial's period: paid when issued, never sent to Payment (Slice 1R) |
| VAT point | The invoice date (H8) |
| Adjustment invoice | The invoice that charges an upgrade at once (H5) |

## Collections (`collections`)

| Word | Meaning |
|---|---|
| Case | One per account: opened on a declined payment, closed when the invoice is paid (`design.md` §5) |
| Schedule | Finance's steps: retry on day 3 and day 7, overdue on day 7, block on day 10, write off on day 30 (H4) |
| Overdue | Unpaid after the second retry |
| Write-off | Debt given up; the account is closed. A payment after write-off clears the debt but does not bring the subscription back (H11) |

## Payment (`payment`)

| Word | Meaning |
|---|---|
| Attempt | One try to collect one amount for one reference |
| Reference | What the money is for; meaningless to Payment (R1). A reference is charged at most once (P1) |
| Attempt states | REQUESTED, SENT, then SUCCEEDED, DECLINED, TECHNICAL_FAILURE or UNKNOWN (`design.md` §5) |
| Decline | The provider refused the charge. Only a decline starts Collections (rule 9) |
| Technical failure | The provider erred or refused before charging; retried after back-off, publishes nothing (ADR-013) |
| Unknown | The provider did not say whether it charged. Never retried blindly; resolved by reference, then by repeating with the attempt's key while the provider keeps it, then never (ADR-026) |
| Unrecognised | A value of a shared enum that the reader does not know, where UNKNOWN is a real state (ADR-024) |
| Token | The provider's stand-in for a card; Tally never holds a card number (C2, P3) |

## Account (`account`)

| Word | Meaning |
|---|---|
| Account | Who pays |
| Standing | Good, overdue, blocked, written off. Only Collections changes it (R3) |
| Blocked | A standing: no new invoices, and Tally-billed sources of access stop counting (I6; Access rule 2) |
| Time zone | The account's zone; it decides what "due" means (H8) |

## Platform and events (`platform`, `shared`)

| Word | Meaning |
|---|---|
| Money | Minor units plus ISO currency; rounding only in `times(Ratio)`, half up (ADR-017) |
| Envelope | Event ID, occurrence time, account ID and sequence, carried by every event (ADR-016) |
| Durable queue | A table of work to do later, claimed with `FOR UPDATE SKIP LOCKED` (FR-017, ADR-003) |
| Duplicate guard | A unique constraint in a business table that permanently stops the same work twice; a queue key is not one (FR-037, rule 11) |
| Money queue | A queue whose work moves money or decides what a customer owes; its failed items alert (FR-037) |
| Idempotency key | A UUID a client sends with every state-changing request, claimed before acting (ADR-019, ADR-027) |
