# Fake provider: the contract it assumes

The fake provider (`com.fakeprovider`) stands in for a real card payment provider in every test (brief section 6, FR-020). Tally is only as correct as the assumptions the fake makes. This file lists every one of them, so that each can be checked against the real provider before it is chosen or integrated.

Each assumption below says:
- **Fake:** what the fake does.
- **Tally relies on it in:** the code that depends on it.
- **If a real provider differs:** what goes wrong.
- **Check:** how to confirm it against the real provider's sandbox or documentation.

Assumptions marked **critical** decide whether a customer is charged twice, charged without Tally knowing, or not charged while access is granted.

## Questions for a real provider, with the evidence so far

Reference provider: Stripe's public documentation (FR-030; `docs/briefs/slice-1-review.md`, part 2, point 5). Sources are listed at the end.

**Q-A. Does a 503 mean the provider did not charge?** (A6)
- **Answer for Stripe: no.** Stripe says to treat a `500` as indeterminate, and groups 500, 502, 503 and 504 together as server errors. After an incident, it may roll a failed request forward and notify later.
- **Consequence:** our 503 rule (retry it) holds only for this fake provider (FR-030). Slice 2's idempotency keys remove it.
- **Contrast, a 429 is safe:** Stripe's rate limiters run before its idempotency layer, so a 429 request was not executed (A7). Network errors leave the client not knowing whether the request arrived (A10).

**Q-B. How long until a charge appears in a lookup?** (A10, A11)
- **Answer: open.** The documentation reviewed does not say.
- Reconciliation's first check at 5 minutes assumes it is less than that. Confirm with the chosen provider before going live.
- Since Slice 1R PR-8, Tally never reads "not found" as "no charge", so the answer no longer decides a double charge: an unknown attempt with nothing found is repeated with its key while the key is kept, and never repeated after (A11, A23). It still decides how soon a lookup can resolve an attempt.

**Q-C. What does a token need for renewals without the customer present?** (A17; driver D15)
- **Answer:** card networks distinguish customer-initiated from merchant-initiated charges. Where strong customer authentication applies:
  - the card must be saved for later use, with the customer's agreement, at the first checkout (Stripe: SetupIntents);
  - a bank may still demand authentication at renewal.
- **Consequence:** a third failure reason, "the customer must come back", which is neither soft nor hard. The fake has no mode for it yet (slice 2 design).
- **Lesson:** an anticorruption layer hides a provider's vocabulary. It cannot hide obligations set by card networks or regulators; those reach into Tally's own model.

## 1. Request and response shape

**A1. One synchronous call charges a stored token, and the answer is final.**
- **Fake:** `POST /charges {token, amount, currency, reference}` answers `200 {chargeId, reference, outcome}`. The outcome is `SUCCEEDED`, `DECLINED_SOFT` or `DECLINED_HARD`, and it never changes afterwards.
- **Tally relies on it in:** `HttpPaymentGateway.charge`, `CollectionWorker`, and the attempt states (`PENDING → IN_FLIGHT → SUCCEEDED / FAILED / UNKNOWN`).
- **If a real provider differs:** real providers have more states: pending or processing, requires action (3-D Secure or SCA), separate authorise and capture steps, and asynchronous confirmation by webhook. Tally has no state for "accepted, outcome later", so it would treat these as `UNKNOWN` or fail on parsing (`Unknown provider outcome`).
- **Check:** list every status the charge API can return for a merchant-initiated recurring charge, and whether any of them is final only after a webhook.

**A2. Three outcomes, already classified as soft or hard.** (critical)
- **Fake:** the provider itself says whether a decline is soft (worth retrying later) or hard.
- **Tally relies on it in:** `HttpPaymentGateway.result`, `FailureReason`, the invoice's `PAYMENT_FAILED` state, and slice 2's dunning.
- **If a real provider differs:** real providers return decline codes (insufficient funds, do not honour, lost card, fraud, and so on), and Tally must classify them. A hard decline classified as soft means Tally retries a lost or stolen card, which card networks penalise.
- **Check:** get the provider's full list of decline codes, and agree the soft/hard mapping with finance.

**A3. Amounts are integers in minor units, with an ISO 4217 currency code.**
- **Fake:** stores `amount` as given and never validates the currency.
- **Tally relies on it in:** `shared.Money` (CLAUDE.md rule 10) and `ChargeRequest`.
- **If a real provider differs:** some providers take decimal major units. Currencies with 0 or 3 decimal places (JPY, KWD) need the right exponent. AED and USD both have 2, so slice 1 never exercises this.
- **Check:** confirm the amount format and the minor-unit exponent for every currency Tally sells in. Confirm that AED is supported for recurring card charges.

**A4. The request carries Tally's reference, and the provider stores it with the charge.**
- **Fake:** `reference` is a top-level field, kept on every charge, including declined ones.
- **Tally relies on it in:** reconciliation (A9), and Payment's rule that it reports by reference only (FR-014).
- **If a real provider differs:** most providers put merchant references in free-form metadata, sometimes with length or character limits.
- **Check:** confirm where the reference goes, its limits, and that it is stored on declined charges too.

**A5. No authentication, plain HTTP, no request signing.**
- **Fake:** anyone who can reach it can charge.
- **Tally relies on it in:** `HttpPaymentGateway`, which sends no credentials.
- **If a real provider differs:** every real provider needs an API key or OAuth, over TLS. The key is a secret that must not be in configuration files.
- **Check:** decide where the key comes from (secrets manager), and how it is rotated.

## 2. Failures: charged or not?

These decide rule 9 ("unknown outcomes are never retried") and decision D7 of the slice 1 report.

**A6. A `503` means the provider did not charge.** (critical)
- **Fake:** in `OUTAGE` mode, it answers 503 before touching the ledger.
- **Tally relies on it in:** `HttpPaymentGateway` (`NotChargedException.Why.UNAVAILABLE`); the attempt goes back to `PENDING` and is retried (D7, R4).
- **If a real provider differs:** a 503 or 502 from a load balancer or gateway in front of the provider can arrive after the charge was made. A retry is then a second charge, because slice 1 sends no idempotency key (A8).
- **Check:** answered for Stripe (Q-A): no 5xx guarantees it. Valid only against this fake (FR-030), until slice 2's idempotency keys.

**A7. A `429` means the provider did not charge, and `Retry-After` is in seconds.**
- **Fake:** answers 429 before touching the ledger, with `Retry-After: 1`.
- **Tally relies on it in:** `HttpPaymentGateway.retryAfter`, the queue's back-off, and the rate limiter (report R6).
- **If a real provider differs:** `Retry-After` may be an HTTP date (Tally then falls back to 1 s), or missing. A provider may also rate-limit by returning a decline code instead of a 429.
- **Check:** Stripe's rate limiters run before the request executes (Q-A). Still to confirm for the chosen provider: the `Retry-After` format.

**A8. Without an idempotency key, the provider does not deduplicate: the same reference sent twice is charged twice.** (critical)
- **Fake:** every `POST /charges` without an `Idempotency-Key` that passes the outage and rate checks records a new charge, whatever the reference. With a key, see A22.
- **Tally relies on it in:** nothing; Tally protects itself instead. One attempt per reference (`PaymentService.requestCollection`), an `UNKNOWN` outcome is never retried (rule 9), and `/admin/charges/count` proves no double charge in AT-4, AT-5 and AT-9.
- **If a real provider differs:** a provider with idempotency keys is better, not worse (A22).
- **Check:** answered for Stripe (A22): keys exist, are kept 24 hours, and a repeat replays the first response.

**A9. A refused connection or a connect timeout means the request never arrived.**
- **Fake:** this is a property of TCP, not of the fake. AT-4 stops the fake.
- **Tally relies on it in:** `HttpPaymentGateway.neverSent`; the attempt goes back to `PENDING`.
- **If a real provider differs:** this holds for any provider. It does not hold for a TLS-terminating proxy that Tally reaches before the provider: the proxy accepts the connection, then fails.
- **Check:** list every proxy between Tally and the provider.

**A10. A read timeout may mean a charge was made, and the charge is visible to lookup once it has been made.** (critical)
- **Fake:** in `TIMEOUT_AFTER_CHARGE` mode, it records the charge, then sleeps 6 s, past Tally's 5 s read timeout.
- **Tally relies on it in:** the `UNKNOWN` state and reconciliation (FR-018, Spike B).
- **If a real provider differs:** a real provider may still be processing when Tally times out, and may finish minutes later. Reconciliation's first check (5 minutes) could then find nothing and mark the attempt `FAILED(SOFT)` (D8), and the charge appears afterwards. The customer is charged, and the invoice says payment failed.
- **Check:** open (Q-B). Ask for the longest time between a charge request and the charge becoming visible, then set reconciliation's first check above it.

## 3. Finding the truth (reconciliation)

**A11. Charges can be looked up by Tally's reference, immediately and consistently.** (critical)
- **Fake:** `GET /charges?reference=` reads the in-memory ledger, so a charge is visible the moment it is recorded.
- **Tally relies on it in:** `HttpPaymentGateway.chargesFor` and `Reconciliation`. Spike B's "resolved in 5 minutes" depends on it.
- **If a real provider differs:** search by metadata is often backed by an index that lags by seconds to minutes, or is not offered at all, which leaves webhooks or settlement files as the only source.
- **Check:** open (Q-B). Confirm that a lookup by reference exists, and its consistency delay. If there is none, reconciliation must use webhooks or reports (and FR-018 needs revisiting).
- **Assumed by Tally since Slice 1R PR-8 (proposed ADR-026):** a charge found decides; "not found" decides nothing. Reconciliation then repeats the attempt's charge with its key (A23) while the key is inside the window, and never after. A lagging search therefore costs time, never a second charge.
- **Fake:** `LOOKUP_LAG` mode makes the lookup show no charge at all, as a search that lags behind would (`UnknownResolutionIT`).
- **Assumed by Tally since #26 (proposed ADR-026, clarified):** the lookup by reference reflects a charge long before Collections' retry delay (3 days). The lookup shows every attempt's charges, so it decides only for the latest attempt of a reference, and `collectAgain` looks the reference up before each retry: a charge missing from the lookup at that moment means a second charge. **Check during selection:** the provider's stated search lag.

**A12. The lookup's answer is the truth; a charge response can be wrong.**
- **Fake:** in `LIE` mode, the response shows the opposite of the recorded outcome, while the lookup shows the recorded one.
- **Tally relies on it in:** reconciliation of `FAILED` attempts (Spike B, B3).
- **If a real provider differs:** this models a provider bug. A real provider's two APIs may share the same bug. Spike B's B4 (a decline reported as a success) shows that Tally does not re-check successes at all (report R2).
- **Check:** confirm which source the provider calls authoritative: API, webhook, or settlement report.

**A13. Declined charges are recorded and returned by the lookup.**
- **Fake:** every charge, whatever its outcome, is in the ledger.
- **Tally relies on it in:** reconciliation, which resolves an `UNKNOWN` attempt to `FAILED` with the provider's soft or hard reason (Spike B, B2). With no record at all, the attempt becomes `FAILED(SOFT)` (D8).
- **If a real provider differs:** some providers keep declines only in logs. Every unknown decline then becomes `FAILED(SOFT)`, so dunning will retry hard declines.
- **Check:** look up a declined sandbox charge by reference.

**A14. The lookup keeps working during an outage of the charge API.**
- **Fake:** `OUTAGE` returns 503 for `POST /charges` only; `GET /charges` still answers.
- **Tally relies on it in:** nothing that a test requires: reconciliation backs off when the lookup fails. But AT-4 never exercises a lookup outage.
- **If a real provider differs:** a real outage likely takes the lookup down too. Resolution of `UNKNOWN` attempts then waits one hour, then a day (Spike B), past D3's target.
- **Check:** see the provider's status history for partial outages.

**A15. There is no settlement report.**
- **Fake:** none. Only per-reference lookups.
- **Tally relies on it in:** nothing yet. The daily totals check (D3) and report R2 need one.
- **Check:** confirm the report's format, delivery time and time zone (cut-off).

## 4. Tokens and cards

**A16. A token is created by the provider's hosted form, and the card never reaches Tally** (FR-015).
- **Fake:** `POST /tokens` returns `tok_<uuid>`, with no card details, standing in for the hosted form.
- **Tally relies on it in:** `AccountApi.recordPaymentToken` and checkout.
- **Check:** confirm that the integration keeps Tally out of PCI DSS scope (SAQ A).

**A17. A token can be charged again and again, with no customer present, and never expires.** (critical for renewals)
- **Fake:** tokens are reusable forever; there are no mandates, SCA exemptions or card updates.
- **Tally relies on it in:** renewals, which charge the stored token every month.
- **If a real provider differs:** recurring charges need a stored-credential or mandate agreement, an initial customer-present charge, and SCA exemptions for merchant-initiated transactions (in the EEA and UK). Cards expire; providers offer account updaters.
- **Check:** partly answered (Q-C). Save the card for later use at checkout, with the customer's agreement, and expect a third failure reason, "authentication required" (D15). Still to confirm: the rules in each market Tally sells in, including the UAE.

**A18. An unknown token is a hard decline, answered `200`.**
- **Fake:** `DECLINED_HARD`, with a charge recorded.
- **Tally relies on it in:** nothing specific.
- **If a real provider differs:** real providers usually answer 4xx, which Tally currently treats as `UNKNOWN` (any other error).
- **Check:** see what the sandbox returns for an invalid or deleted token. Map 4xx validation errors to "not charged".

## 5. Limits and timing

**A19. The rate limit is a fixed one-second window, counting charge requests only, shared by all callers.**
- **Fake:** `RATE_LIMIT(n)` allows n `POST /charges` per wall-clock second; lookups are not counted.
- **Tally relies on it in:** `ProviderIsolation`'s limiter, sized at 95% of the limit, split evenly across instances (report R6), and AT-10.
- **If a real provider differs:** real limits may be sliding windows or token buckets, may count lookups (reconciliation then competes with charges), and may be per key or per account.
- **Check:** get the limit, the algorithm and what counts towards it, in writing.

**A20. Normal latency is near zero; slowness comes after the charge.**
- **Fake:** `SLOW(ms)` sleeps after recording the charge, so a slow answer is still a charge.
- **Tally relies on it in:** the 5 s read timeout and the 15 payment workers per instance (bulkhead), sized for throughput (Q2).
- **If a real provider differs:** a 99th-percentile latency of 1–2 s halves the throughput per worker.
- **Check:** measure sandbox and production latency percentiles, and size workers from them.

**A21. Random declines are independent per call.**
- **Fake:** `DECLINE_SOFT(p)` and `DECLINE_HARD(p)` decline each call with probability p.
- **Tally relies on it in:** tests only.
- **If a real provider differs:** real declines cluster: by card, by issuer, by time of day. A renewal run concentrated on the 1st of the month may hit issuer limits.
- **Check:** ask for decline rates by issuer, and time of day, for recurring charges.

## 6. Idempotency keys (Slice 1R, spike S-2)

Modelled on Stripe's API v1 (sources at the end). Tests: `IdempotencyKeysTest`.

**A22. With an `Idempotency-Key` header, the provider saves the first response once the charge has executed, and replays it for every repeat within 24 hours, declines included.** (critical)
- **Fake:**
  - A repeat with the same key and the same parameters returns the saved status and body, and records no charge. `/admin/stats` counts it as `replayed`.
  - The same key with other parameters: `400 {"error":"idempotency_error"}`, nothing charged.
  - A repeat while the first request still executes: `409`, not saved.
  - The outage (`503`) and the rate limit (`429`) stand in front of the idempotency layer: their refusals are not saved, so the same key may charge later.
  - After 24 hours a key is forgotten, and reusing it charges again. Tests simulate this with `POST /admin/idempotency-keys/prune`.
- **Tally relies on it in:**
  - every charge, since Slice 1R PR-8: each attempt sends its own key, the reference plus the attempt number (`reference:number`), stored with the attempt with the token, amount, currency and reference it was sent with (`PaymentCollectionIT.anAttemptSendsItsOwnKeyAndARepeatWithItIsReplayed`). It never reuses an earlier attempt's key, because the provider would replay that attempt's decline (`aDeclineIsReplayedForTheSameKeyAndANewKeyChargesAgain`; AT-16).
  - the resolver of unknown attempts (A23).
- **If a real provider differs:**
  - Stripe's API v1 matches, with one difference: Stripe also saves and replays a `500`. The fake's `503` is not saved, because it models a gateway in front of the provider (A6).
  - Stripe's API v2 retries a failed request instead of replaying it, and keeps keys for 30 days.
  - A provider without keys: only A8 holds, and Tally's one-open-attempt rule is the only guard.
- **Check:** the header name, the retention period, which responses are saved, and how parameters are compared.

**A23. The outcome of a request is learnt by key: repeating it with the same key and the same parameters returns the saved response without charging again.** (critical)
- **Fake:** as A22. There is no endpoint that looks a charge up by key; the repeat is the lookup (`afterATimeoutTheSameKeyReturnsTheRecordedChargeWithoutChargingAgain`).
- **Tally relies on it in:** `Reconciliation`, since Slice 1R PR-8 (proposed ADR-026), after the lookup by reference (A11) found no charge:
  - (b) while the attempt's key is younger than `tally.payment.provider.key-repeat-window` (20 hours: the 24-hour retention minus a margin), the charge is repeated with the same key and the stored token, amount, currency and reference. A replay or a fresh outcome decides; a refusal, a `409` or a timeout leaves the attempt UNKNOWN for a later check;
  - (c) past the window, it is never repeated: it stays UNKNOWN, counted in `tally.payment.unknown_past_key_window`, for settlement reconciliation.
  - Tests: `UnknownResolutionIT` (a never-executed first request, a lagging lookup, a key past the window with the provider's keys pruned, a refused repeat).
- **If a real provider differs:** Stripe has no lookup by idempotency key either. A repeat is a status query only while the key is kept, and has three edges:
  - if the first request never executed, the repeat charges now. Still at most once, but the resolver never sees "no such charge"; it sees a new outcome;
  - after 24 hours the key is gone, and a repeat is a new charge, even if the first one charged: a double charge. The resolver must stop using the key before the retention ends, and fall back to the lookup by reference (A11);
  - while the first request still executes, a repeat gets `409`: no answer yet, ask again later.
  - In every case the repeat must carry exactly the first request's parameters, so an attempt must store them.
- **Check:** confirm that no lookup by key exists, and the retention period.
- **Check, for provider selection:** can each charge carry metadata (Tally's attempt number) that the lookup by reference returns? If so, the resolver can match each record to its attempt, an exact match. That is the real fix: once confirmed, the rules "a decline found decides only the first attempt" and "the lookup decides only for the latest attempt" (proposed ADR-026, #26) can both be replaced by it.

## 7. Things the fake does not model at all

None of the following exist in the fake, so no test covers them. Each needs a decision before production.
- **Webhooks**, or any asynchronous notification.
- **Refunds**, partial or full.
- **Disputes and chargebacks**: money taken back weeks later, which invoicing and entitlement must react to.
- **Partial approvals**: an amount lower than requested.
- **Currency conversion** by the provider.
- **Key retention in real time**: the fake keeps keys for 24 hours of its own wall clock; tests prune them instead of waiting (A22).
- **Provider-side retries** (for example "smart retries"), which would conflict with Tally's dunning.
- **Persistence**: the fake loses everything on restart, so a test cannot restart the provider mid-run.
- **Clock**: the fake stamps charges with its own wall clock (`Instant.now()`), not Tally's test clock. Tally never reads that timestamp, and must not start to without a provider-side clock contract.

## Sources
- Stripe, Advanced error handling: https://docs.stripe.com/error-low-level
- Stripe, Errors: https://docs.stripe.com/api/errors
- Stripe, The Setup Intents API: https://docs.stripe.com/payments/setup-intents.md
- Stripe, Idempotent requests: https://docs.stripe.com/api/idempotent_requests
- Stripe, API v2 overview (idempotency in v2): https://docs.stripe.com/api-v2-overview

## Test-only endpoints

These belong to the fake, not to any real provider. Tally's production code must never call them:
- `POST` and `GET /admin/mode`
- `GET /admin/charges/count`
- `GET /admin/requests/count`
- `GET /admin/stats`
- `POST /admin/idempotency-keys/prune`
