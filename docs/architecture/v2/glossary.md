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
| Offer version | A published offer, which never changes; a change makes a new version (O2). Each version has an effective-from date (ADR-047). A new subscription signs up to the version in force on the account's local date; a subscription keeps the version it signed up to (Slice 1R, WAR-65) |
| Kind of offer | Discount, free trial, fixed-price phase, bundle price; anything else is a new kind, and needs finance's sign-off before publication (O1, H1) |
| Phase | A stretch of time with one price, such as "first month free" |
| PriceQuote | The answer to "what does this subscription owe for this period, and why": amount lines, currency, period and reason. The same subscription, period and version always give the same PriceQuote (O3) |
| Reason (QuoteReason) | Offer, offer version, kind and flags behind a PriceQuote; stored on the invoice so a charge can be explained (D7) |
| Proration | By days: unused days are credited, remaining days are charged at the new price (O4) |

## Feature flags (`flags`)

| Word | Meaning |
|---|---|
| Feature flag | A named switch in `flags` (`ADR-010`, `ADR-048`, `ADR-049`), off by default, evaluated as account assignment, then country assignment, then default; changes are logged, persisted as a snapshot (`V56__flags.sql`), and every defined flag's state is recorded on `QuoteReason` |
| Full release | Explicit release of a flag once its default is on and no assignment is off; starts a 30-day clock after which the flag is overdue and must be removed (`ADR-010`, `ADR-048`) |

## Subscription (`subscription`)

| Word | Meaning |
|---|---|
| Subscription | One plan bought by one account |
| Period | The stretch of time one invoice covers, `[start, end)`, numbered from 1. Periods never overlap or leave gaps (S2) |
| Renewal | Creating the next period; `renew(n)` creates period n once and changes nothing if it exists |
| Billing day | The day each period starts; set by the account's first subscription and then unchanged (S5, ADR-043) |
| Billing time | 00:00 on the billing day in the account's time zone, when a renewal falls due (H8, `BillingCalendar`) |
| Plan change | An upgrade or downgrade, with its effective date. Upgrade at once; downgrade at period end (S3) |
| Cancellation | An end date, always a period end; no refund for the current period (S4, H11) |
| States | TRIAL, ACTIVE, CANCELLING, ENDED; ENDED refuses everything (`design.md` §5). Slice 3 builds ACTIVE and ENDED only (proposed ADR-038) |
| Ended | A subscription that will never renew: written off (`WRITE_OFF`) or its first invoice declined (`FIRST_PAYMENT_DECLINED`, 025). The account may sign up again: one **live** subscription per account |
| Waiting renewal | A renewal due while the account is BLOCKED: no new period and no invoice; it runs once the standing is GOOD again, and the period keeps its dates (decision 3, I6) |

## Access (the deployable in `access/`, `com.tallyaccess`)

A separate deployable since Slice 2 (ADR-001), with its own database.

| Word | Meaning |
|---|---|
| Subscriber | The person who watches. In Slice 2 the subscriber is the account (ADR-033) |
| Source of access | A subscription, a trial or a partner bundle. A subscriber can watch if at least one source is active |
| Suspended | Viewing stopped; billing facts unchanged |
| Activating | What the app shows while a new subscriber's access arrives; 99% within 5 seconds (ADR-014) |
| Access facts | The current facts per account that billing publishes to Access, IDs, enums, numbers and times only: standing and sources, with a sequence (ADR-006, ADR-012; `contracts/access-facts/v1/`) |
| Copy | Access's own store of the access facts, one row per account, from which it answers (`access.facts`) |
| Sequence | The number of an account's facts: it goes up by one only when the facts change. Access applies a message only if its sequence is higher than the one it holds (ADR-033) |
| Position | Where Access has read to in each partition of the log, kept in its own database with the facts (`access.log_position`, ADR-035) |
| Ready | An instance may answer: its copy has reached the end of the log it saw at start, or the copy was complete once. Not ready: it answers 503 (ADR-036) |
| Complete once | The mark in Access's database that its copy once reached the end of the log (`access.copy_state`). It counts only with a stored position (ADR-036) |
| Rebuild | Building the copy again from the log: the three tables emptied in one statement, then Access started with its usual consumer group (`docs/runbooks/access-rebuild.md`; AT-19) |
| Value not known | A schema version, standing, source kind or source state Access does not know. It never takes access away, and is counted (ADR-033) |

## Access feed (`accessfeed`)

Billing's side of the access facts (ADR-033). It holds no access rule.

| Word | Meaning |
|---|---|
| Account facts | One row per account in `accessfeed.account_facts`: standing, sources and sequence, built from `SubscriptionChanged` and `StandingChanged` |
| Relay | The worker on the platform queue that sends an account's current facts to the log: for one account, one send at a time, each a Kafka transaction of its own (CLAUDE.md rule 15) |
| Sent sequence | The last sequence of an account that reached the log (`sent_sequence`). Facts with a higher sequence are unsent; the age of the oldest is `AccessFeedBehind`'s measure |
| Event log | The compacted topic `access-facts`, keyed by account ID, that carries the facts from billing to Access (ADR-006, ADR-012) |

## Invoicing (`invoicing`)

| Word | Meaning |
|---|---|
| Invoice | A legal record of what an account owes for a period. At most one period invoice per subscription per period; an adjustment invoice is a separate document with its own ID, tied to the period (I2, ADR-040) |
| Line | One amount from a PriceQuote (I3) |
| Credit note | The only way to correct an issued invoice; an issued invoice never changes (I1) |
| Reference | The opaque label Payment collects against |
| Zero invoice | An invoice of zero, such as a free trial's period: paid when issued, never sent to Payment (Slice 1R) |
| VAT point | The invoice date (H8) |
| Adjustment invoice | The invoice that charges an upgrade at once (H5) |
| Tax quote | What `TaxRates` answers for an invoice: a rate and a description, from the jurisdiction of the account's country. A zero rate (such as the US test default, EXEMPT) adds no tax line (`ADR-050`) |
| Tax line | The one line appended to an invoice for VAT: each pre-tax line is taxed on its own and rounded half up (`ADR-017`), and the rounded amounts are summed |
| Draft invoice | An invoice whose tax could not be quoted yet. It has no issue date, sends nothing to Payment and publishes no `InvoiceIssued` (`ADR-050`) |
| Tax-quote queue | The money queue (`invoicing.tax_quote_queue`) that retries a draft invoice until tax answers; an invoice is never issued without tax (`ADR-050`) |
| Invoice states | `DRAFT`, then `TAX_QUOTED`, then `PAYMENT_PENDING` (or `PAID` when the total is zero) (`ADR-050`) |

## Collections (`collections`)

| Word | Meaning |
|---|---|
| Case | One per account, covering every unpaid invoice of the account: opened on a declined payment of a renewal, closed when none of its invoices is unpaid (`design.md` §5; proposed ADR-037). A declined first invoice opens no case: the subscription ends (025) |
| Schedule | Finance's steps, counted from the case's first decline: retry on day 3 and day 7, overdue one hour after the day-7 retry is asked, block on day 10, write off on day 30, each only if a debt is still open (H4) |
| Step | A timer of the schedule that changes the case's state, and the standing with it: OVERDUE, BLOCKED, WRITTEN_OFF. It acts on its rule from the state it finds, after asking Invoicing what is still unpaid |
| Overdue | Unpaid one hour after the second retry was asked: a state of the case, and a standing |
| Write-off | Debt given up on day 30: the subscription ends, and the standing stays BLOCKED until the debt is paid (decision 4). A payment after write-off clears the debt and makes the standing GOOD, but does not bring the subscription back (H11) |
| Pay now | The household asks Tally to try the payment again, with the card the account has, for every unpaid invoice of its case, in any state with a debt open (decision 2). A decline changes no state |
| Retry number | One sequence for each case: every request, scheduled or "pay now", takes the next number at the moment it is asked (#80, option B) |

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
| Token | The provider's stand-in for a card; Tally never holds a card number (C2, P3). Not a caller's token (Platform) |

## Account (`account`)

| Word | Meaning |
|---|---|
| Account | Who pays |
| Standing | Good, overdue or blocked. Only Collections changes it (R3), in the same transaction as the case (rule 5). A written-off case keeps the standing blocked |
| Blocked | A standing: no new invoices (a renewal waits), and Tally-billed sources of access stop counting (I6; Access rule 2) |
| Time zone | The account's zone; it decides what "due" means (H8) |
| User | The person a token names: its subject (`sub`), an ID from the identity provider. Tally keeps no name and no email (ADR-032) |
| Account link | The one row in `account.account_user` that ties a user to the user's account. One user, one account: the constraint `account_user_one_per_user` (WAR-84; ADR-032) |
| Another household | An account, subscription or payment that is not the caller's. A request for it answers 404, the same as for one that does not exist, and changes nothing (rule 16) |

## Platform and events (`platform`, `shared`)

| Word | Meaning |
|---|---|
| Money | Minor units plus ISO currency; rounding only in `times(Ratio)`, half up (ADR-017) |
| Envelope | Event ID, occurrence time, account ID and sequence, carried by every event (ADR-016) |
| Durable queue | A table of work to do later, claimed with `FOR UPDATE SKIP LOCKED` (FR-017, ADR-003) |
| Duplicate guard | A unique constraint in a business table that permanently stops the same work twice; a queue key is not one (FR-037, rule 11) |
| Money queue | A queue whose work moves money or decides what a customer owes; its failed items alert (FR-037) |
| Idempotency key | A UUID a client sends with every state-changing request, claimed before acting (ADR-019, ADR-027). It belongs to the caller: its scope is `user:<id>` (Slice 4 PR-5) |
| Identity provider | The service that signs in a user and issues tokens. No product is chosen; Tally reads its issuer and the location of its key set from settings (ADR-032) |
| Token (of a caller) | A signed JWT from the identity provider on every request under `/v1/`. Tally checks its signature, issuer, audience and expiry itself, the expiry on the injected clock (rule 16). Not the payment token, the provider's stand-in for a card |
| Caller | Who sends a request: the token's user and, once found, the user's account (`platform.Caller`). Found once for each request; every `/v1/` handler takes it (F16) |
| Service token | A token for a service, not a household: the streaming platform's token for Access has audience `tally-access` and scope `access:read` (Slice 4 brief, decision 1). A household's token gets 403 at Access |
| Key set | The identity provider's public keys (JWKS), fetched from its key set location and used to check signatures. Billing keeps it in memory (ADR-039, option B); Access keeps the last one in its database, `access.identity_key_set` (option A) |
| Hold-off | After any fetch of the key set, Access uses the set it keeps for this time (30 s) before it fetches again, so tokens with unknown key IDs cannot hold every token behind a provider that hangs. A key the provider adds is accepted at most one hold-off after it is first seen (review of #92) |
