# Slice 1R brief: reshape the skeleton to the second design

## Goal

The architecture was designed a second time, from zero, in sixteen steps. The result is the **Tally Design Record** (outside this repository). Its decisions are summarised in `docs/architecture/v2/`.

This slice reshapes the existing walking skeleton to match that design. It is a refactoring slice with a few new behaviours. It is not a rewrite.

Keep what already matches. Change what does not. Report every place where the code teaches us the design is wrong.

## Why reshape, not rewrite

The first run built a working Slice 1: queues, job health, attempt-first payments, a fake provider, two-stage CI and twelve fitness functions. Most of it matches the second design. Its spikes also found real library behaviour that the second design now relies on (ADR-016). Rewriting would throw that away.

## Read first

1. `docs/architecture/v2/README.md`: the second decision log, and the mapping from every first-run record (now `FR-NNN`).
2. `docs/architecture/v2/drivers.md`: the drivers. Numbering differs from the first run.
3. `docs/architecture/v2/design.md`: contexts, rules, the dependency graph, public APIs and events, state machines, Money, the REST contract.
4. `CLAUDE.md`, after PR-1 updates it.

## In scope

- Archive the first-run records and adopt the second log.
- Module changes: `catalog` becomes `offers`; `entitlement` becomes `access`; a new `collections` module; `checkout` is removed.
- The billing calendar moves from Invoicing to Subscription (`renew(n)`).
- Money gains `Ratio`, one rounding rule and allocation.
- Offers gain versions, kinds and the pricing extension point, with two rules.
- Payment attempts move to the second design's six states.
- Collections opens a case on a declined payment and runs the day-3 retry only.
- Sign-up through `POST /v1/subscriptions` with an idempotency key.

## Out of scope (later slices)

- Extracting Access into its own deployable, the event log and the compacted access-facts topic (ADR-001, ADR-006, ADR-012). **That is Slice 2.** In this slice Access stays in process, behind the boundary described in PR-10.
- Collections beyond day 3: overdue, blocked, written off.
- Upgrades, downgrades, proration in invoices, VAT.
- Refunds, chargebacks, settlement reconciliation (D14), strong customer authentication (D15).
- The support view.

## Pull requests, in order

Each is a short-lived branch, merged through a pull request (FR-038, now ADR-008). `./mvnw verify` is green on every commit. Show each new behaviour test failing first.

**No production database exists.** Two PRs below rename schemas in one step, which the expand-and-contract rule (ADR-009) would normally forbid. Say so in each PR description. If a shared database does exist, stop and ask.

### PR-1 · Docs: adopt the second record (no code)
- Move `docs/architecture/adr/*` to `docs/architecture/archive/first-run/`, renaming each file `FR-NNN-...`. Do not edit their decision text. Add one line to each Notes section: "Archived; see `docs/architecture/v2/README.md`".
- Move `docs/architecture/drivers.md` to the archive the same way.
- Keep `docs/architecture/v2/` as delivered.
- Update `CLAUDE.md` as listed in **CLAUDE.md changes** below.
- Replace `module-dependencies.txt` with the graph in `design.md` §3. The pinned-graph test (FR-040) will fail until PR-3 to PR-10 land, so mark the new graph as the target and keep the test checking the **current** graph until PR-10, then switch it. Say how in the PR.

### PR-2 · Money (ADR-017)
- Add `Ratio(long numerator, long denominator)`, exact.
- Add `Money.plus`, `minus`, `times(Ratio)` (half up, the only place rounding happens) and `allocate(int... weights)` (largest remainder; parts always sum to the whole).
- Minor units come from `Currency.getDefaultFractionDigits()`. Never hard-code 100.
- Tests: the golden examples in `design.md` §6; a KWD case with three decimals; allocation always sums exactly (property test).
- Fitness function F4: `shared` contains only `Money`, `Ratio`, `DomainEvent`, and the list is pinned.

### PR-3 · Rename `catalog` to `offers`
- Package, schema, `package-info.java`, Flyway migration. Behaviour unchanged.

### PR-4 · Offers: versions, kinds, the pricing extension point (ADR-015)
- `Offer` aggregate with published versions as value objects; `OfferKind` aggregate (experimental, then approved).
- Internal `PricingRule` interface and `PricingRuleRegistry`. Two rules: `FixedPricePhaseRule`, `FreeTrialRule`.
- Public `Pricing.quote(QuoteRequest)` returns the `PriceQuote` in `design.md` §4, including a reason with offer version and flag states.
- Start-up check: every approved kind has a rule, or the application refuses to start.
- Golden tests per rule. Each golden example is run twice to prove the same inputs give the same quote (rule O3).
- Seed the existing three plans as offers of kind `fixed-price-phase`.

### PR-5 · Move the billing calendar to Subscription (Design Record §12, ADR-003)
- `Subscription` holds the current period and its number. `renew(n)` does nothing if period n was already renewed. The version column guards concurrent renewals.
- The billing run claims due subscriptions on Subscription's own queue (FR-017 rules still apply).
- Subscription publishes the renewal. Invoicing listens and issues one invoice with the ID (subscription, period number).
- Remove `BillingCalendar` and `SubscriptionBilling` from `invoicing`. The calendar rules from the first run (day 31, local midnight, daylight saving) move with them, and AT-3 must still pass.
- Map the second design's subscription events onto the existing `SubscriptionChanged` class and its type enum. **Do not rename or remove event classes** (ADR-016). Add new type values only.

### PR-6 · Payment attempts: six states (ADR-013, ADR-018)
- States: `REQUESTED`, `SENT`, `SUCCEEDED`, `DECLINED`, `TECHNICAL_FAILURE`, `UNKNOWN`. Map the first run's states in a migration: `PENDING`→`REQUESTED`, `IN_FLIGHT`→`SENT`, `FAILED` with reason `HARD` or `SOFT`→`DECLINED`, keeping the reason (a soft decline is still the bank's answer about the card). Provider errors that the first run's breaker handled become `TECHNICAL_FAILURE`. If an existing row cannot be classified, stop and ask.
- Add new enum values; keep `UNKNOWN` as the default for unknown input (FR rule 7).
- A `REQUESTED` or `SENT` attempt older than the call timeout becomes `UNKNOWN` and is resolved by key.
- `TECHNICAL_FAILURE` publishes no event to Invoicing. It retries after back-off. The existing breaker (AT-4) stays.
- The transition table lives inside the aggregate. One table-driven test tries every state with every event.
- Payment's aggregate stays one per reference (FR-041).

### PR-7 · Event rules (ADR-016)
- Every event's envelope carries the aggregate version as a sequence number. Add the field; do not remove any.
- The resubmission job handles publications one at a time and isolates a failure, so one unreadable publication never blocks the rest (first-run finding 1). Pin the behaviour in a test (first-run open question Q2).

### PR-8 · Collections, day 3 only (ADR-005, Design Record §16)
- New `collections` module, layered inside (Design Record §10 C).
- On a declined payment, open one case per account. On day 3, ask Invoicing to request payment again. On `InvoicePaid`, close the case.
- Timers are claimed rows with a due time on Collections' own queue.
- Only `collections` may call `AccountStanding.set` (F3). This slice does not change standing yet, but add the rule and its test now.

### PR-9 · Sign-up through the REST contract; remove `checkout` (ADR-019, ADR-014)
- `POST /v1/subscriptions` with a required `Idempotency-Key`. Store the key before acting and return the stored response for 24 hours.
- Errors are RFC 9457 problem details, with the type codes in `design.md` §8.
- The response includes `accessWithinSeconds: 5` (ADR-014).
- Remove the `checkout` module. Its wait-then-poll setting (FR-019) is replaced by the 5-second promise. Write the OpenAPI document first, under `docs/api/`.

### PR-10 · Rename `entitlement` to `access`; prepare the boundary
- Access may depend only on `shared`, `platform`, and **temporarily** on the events of `subscription` and `account`. Mark this edge in the graph as temporary, to be removed in Slice 2.
- Access must not call any billing API. It only reacts to events and answers from its own tables.
- Remove the first run's dependency from `entitlement` on `payment`. Access no longer waits for a first payment; a trial gives access from sign-up (H7).
- Switch the pinned-graph test to the new graph.

## CLAUDE.md changes (in PR-1)

- **Rule 1, modules:** `shared`, `platform`, `account`, `offers`, `subscription`, `invoicing`, `payment`, `collections`, `access`. Remove `catalog`, `entitlement`, `checkout`. Point to `design.md` §3 for the graph.
- **Rule 7, events:** add "Resubmission handles one publication at a time; a failure is isolated (ADR-016)."
- **Rule 9, payments:** replace with "Payment outcomes are succeeded, declined, technical failure or unknown. Only a decline starts Collections. Unknown is resolved by key, never retried. A requested or sent attempt older than the timeout is unknown (ADR-013, ADR-018)."
- **Rule 10, money:** add "`Money.times(Ratio)` is the only place rounding happens: half up. Use `allocate` to split (ADR-017)."
- **New rule 13:** "Every request that changes state over REST requires an `Idempotency-Key` (ADR-019)."
- **New rule 14:** "Inside `offers`, `subscription`, `invoicing` and `payment`, the domain package imports no Spring and no adapter; `jakarta.persistence` annotations are allowed (Design Record §10 C)."
- **Read first:** point to `docs/architecture/v2/`.

## Acceptance tests

Keep the first run's tests where their scenario still holds. Update them to the new modules. In the report, list each one as kept, changed (and how), or retired (and why).

| ID | Status | Scenario | Pass when |
|---|---|---|---|
| AT-1 | changed | Sign-up, happy path, through `POST /v1/subscriptions` | Invoice paid; Access answers yes within 5 s |
| AT-2 | keep | A month of time travel, 100 accounts | One invoice and one attempt per account; test under 2 min (D10) |
| AT-3 | keep | Calendar edges | Day 31, local midnight, daylight saving, now in Subscription |
| AT-4 | keep | Provider outage mid-run | Technical failures; breaker opens; **no Collections case opens**; all charged after recovery |
| AT-5 | keep | Timeout after charge | Unknown, resolved to succeeded; provider charge count = 1 (D2) |
| AT-6 | keep | Lying provider | As before |
| AT-7 | keep | Crash after invoice commit, before listener | Collection requested exactly once (D3) |
| AT-8 | keep | Listener fails | Resubmitted; effect exactly once |
| AT-9 | keep | Two instances, kill one mid-run | One renewal and one invoice per subscription and period (D1) |
| AT-10 | keep | Throughput, scaled | As before (D6) |
| AT-11 | keep | Stop each worker and job | Overdue metric within threshold (D9) |
| AT-12 | keep | Invariant check | As before |
| AT-13 | new | Same sign-up sent twice with one idempotency key | One subscription; identical responses |
| AT-14 | new | Invoice lines equal the PriceQuote | Lines and total match; the reason names the offer version |
| AT-15 | new | Crash after an attempt is saved, before the call returns | Attempt becomes unknown after the timeout; resolved; charged at most once |
| AT-16 | new | Declined payment | One Collections case; on day 3 a new attempt for the same reference; paid closes the case |
| AT-17 | new | One unreadable publication among others | The others are still resubmitted and handled |

## Spikes

- **S-1.** Can Spring Modulith's resubmission be driven one publication at a time with a predicate, catching per row? If not, stop and report.
- **S-2.** The fake provider must model idempotency keys and status lookup by key, both already assumed by the design (Step 15, R1). Add both to `fake-provider/CONTRACT.md` if missing.

## Definition of done

- All ten PRs merged; `./mvnw verify` green on `main`.
- AT-1 to AT-17 pass, or the report says exactly why not.
- The pinned graph equals `design.md` §3, with the one temporary edge marked.
- F3, F4, F8, F9 and F10 exist as tests. The report names each test.
- `docs/briefs/slice-1r-report.md` written in the format CLAUDE.md requires, plus: every place where the code disagreed with the second design, and what you did about it.

## Stop and ask, instead of deciding, when

- A first-run state or rule has no clear home in the second design.
- A change would rename or remove an event class.
- A shared database exists, so a one-step schema rename would break a running version.
- The code shows that a second-design decision is wrong. Report it with evidence. Do not work around it silently.
