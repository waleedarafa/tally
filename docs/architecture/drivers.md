# Tally drivers

Six-part scenarios for the high-priority drivers. *(assumed)* marks a value still to be confirmed by its stakeholder.
D5, D8 and D9 stay as summaries until their slices.

## Business goals
1. No revenue leakage: every renewal charged exactly once.
2. Fast pricing changes: promotions in days, not weeks.
3. New countries, with local currency and tax.
4. No lock-in to one payment provider.
5. Low running cost for a small team.

## Utility tree

| Quality | Driver | Value | Risk |
|---|---|---|---|
| Correctness | D1 Exactly once per billing date | H | H |
| | D3 Never charged twice | H | H |
| | D10 Exact month-end export | H | M |
| Recoverability | D2 No lost work | H | H |
| Performance | D6 Busiest billing day | H | M |
| Operability | D7 Stuck work visible | H | M |
| | D13 Job health | H | M |
| Security | D12 Key rotation | H | M |
| Testability | D4 Moving time | H | M |
| Modifiability | D5 Promotion of a known type live in 2 days, one owner | H | M |
| | D8 Second payment provider without billing changes | M | M |
| | D9 New country tax in one place | M | L |
| Privacy | D11 Deletion versus retention | M | M |
| Correctness | D14 Payment truth matches the provider | H | M |
| Availability | D15 Renewals without the customer present | H | M |

## D1. Exactly once per billing date
- **Source:** billing schedule (clock). **Stimulus:** an account's billing date arrives.
- **Environment:** two instances; one crashes after claiming the row, before finishing.
- **Artifact:** billing schedule queue, invoice creation.
- **Response:** the reaper releases the row; the other instance processes it.
- **Measure:** exactly one invoice per (account, billing date); zero duplicates, zero misses over 1,000,000 simulated accounts.
- **Verified by:** chaos test; unique constraint; daily duplicate check.

## D2. No lost work
- **Source:** infrastructure. **Stimulus:** crash after the invoice commits but before listeners run; or a listener fails.
- **Environment:** billing run in progress. **Artifact:** event publication registry; the payment-request listener.
- **Response:** the event was stored with the invoice; the resubmission job retries it; the listener is idempotent.
- **Measure:** every committed `InvoiceIssued` leads to exactly one collection request; no publication incomplete > 15 minutes.
- **Verified by:** slice 1 spike A; resubmission job health; incomplete-publication alert.

## D3. Never charged twice
- **Source:** provider network. **Stimulus:** a charge times out after the provider charged the card.
- **Environment:** slice 1 (no idempotency keys). **Artifact:** payment attempts, reconciliation.
- **Response:** attempt marked `UNKNOWN`; not retried; reconciliation records the real outcome.
- **Measure:** zero double charges; `UNKNOWN` resolved within 1 hour *(assumed)*; none after 24 hours.
- **Verified by:** fake provider "timeout after charge" and "lie" modes; alert on `UNKNOWN` > 1 hour; daily totals check.

## D4. Tests can move time
- **Source:** developer or CI. **Stimulus:** a test needs behaviour one month or one year later.
- **Environment:** any test level. **Artifact:** all date logic via injected `Clock`.
- **Response:** the test advances the clock; scheduling and date logic follow.
- **Measure:** one-month billing test < 2 minutes in CI *(assumed)*; zero direct `now()` calls; tests for the 31st of a short month, 29 February, daylight saving.
- **Verified by:** ArchUnit clock rule; calendar edge-case suite.

## D6. Busiest billing day
- **Source:** billing calendar. **Stimulus:** 500,000 accounts due on one day *(assumed)*: month end plus a campaign cohort.
- **Environment:** production; two instances; provider limit 100 calls/s; ~5% soft declines *(assumed)*.
- **Artifact:** billing schedule, Invoicing, payment workers.
- **Response:** all accounts invoiced; charges at the provider's maximum rate without a 429 storm.
- **Measure:** 99.9% of invoices within 1 hour of billing time; all first attempts within 3 hours.
- **Verified by:** nightly load test with an uneven, realistic calendar.
- **Slice 1 evidence:** charging 87/s (target rate now 95% of the limit, ADR-036); invoicing about 100/s measured mostly on one instance, against about 139/s needed. Unproven until the staging run. Do not relax this target before measuring it.

## D7. Stuck work is visible
- **Source:** silent processing failure. **Stimulus:** a queue's oldest due item keeps ageing.
- **Environment:** any time. **Artifact:** billing schedule, payment attempts, event publications.
- **Response:** an alert naming the queue, oldest age and count.
- **Measure:** alert within 5 minutes of the oldest item being 5 minutes overdue; < 1 false alarm a week *(assumed)*.
- **Verified by:** chaos test stopping each worker type.

## D10. Exact month-end export
- **Source:** finance. **Stimulus:** month-end export request.
- **Environment:** first business day; last-day events may be in flight.
- **Artifact:** invoice and payment data, the export.
- **Response:** invoices, payments, credits, write-offs per currency, with a reconciliation summary listing every difference.
- **Measure:** totals match to the cent or every difference is explained; ready by 06:00 day one *(assumed)*; repeatable.
- **Verified by:** month-of-data test with in-flight events at midnight. *(Slice 4.)*

## D11. Deletion versus retention
- **Source:** customer. **Stimulus:** personal-data deletion request.
- **Environment:** past invoices must be kept by law. **Artifact:** personal data in every module, provider tokens, logs.
- **Response:** delete or pseudonymise everywhere except legally retained invoice data; delete the provider token.
- **Measure:** within 30 days *(assumed; legal to confirm)*; each module reports completion.
- **Verified by:** end-to-end deletion test searching every schema and log store. *(Slice 6.)*

## D12. Provider key rotation
- **Source:** security. **Stimulus:** suspected key leak.
- **Environment:** billing run in progress. **Artifact:** secrets store, payment adapter configuration.
- **Response:** new key loaded without restart; old key revoked; in-flight calls handled like any failed attempt.
- **Measure:** within 30 minutes *(assumed)*; zero lost renewals; zero restarts.
- **Verified by:** key rotation during the nightly load test.

## D13. Every background job reports health
- **Source:** any background job. **Stimulus:** a job stops or keeps failing.
- **Environment:** any time. **Artifact:** the job's health signal.
- **Response:** last success time and waiting work are reported; overdue jobs alert.
- **Measure:** alert within twice the job interval (minimum 5 minutes); every job has a signal.
- **Verified by:** fitness function; chaos test disabling each job.

## D14. Tally's payment records match the provider's
- **Source:** the payment provider. **Stimulus:** the provider's record differs from Tally's: a decline reported as success, a later refund or chargeback, or a failed request the provider rolls forward after an incident.
- **Environment:** normal operation. **Artifact:** payment attempts, invoices.
- **Response:** the mismatch is detected, recorded, listed and alerted. The action on the invoice and on access follows ADR-032.
- **Measure:** every mismatch detected within 24 hours *(assumed)*, in both directions.
- **Verified by:** settlement reconciliation test with the fake provider's `LIE` and reversal modes. *(Slice 2.)*

## D15. Renewals work without the customer present
- **Source:** the customer's bank and the card network. **Stimulus:** a renewal charge on a saved card.
- **Environment:** the customer is not online. Some regions require strong customer authentication.
- **Artifact:** card setup at checkout, payment attempts, dunning.
- **Response:** checkout saves the card for later use with the customer's agreement. Renewals are flagged as merchant-initiated. If the bank still demands authentication, the attempt fails with a distinct reason (neither soft nor hard), and the customer is asked to come back and complete it.
- **Measure:** the share of renewals that need the customer *(assumed; provider data)*; each such case reaches the customer within 1 hour *(assumed)*.
- **Verified by:** a fake provider mode that demands authentication on renewal. *(Slice 2 design, slice 5 follow-up.)*
- **Note:** rules differ by region and provider. Confirm with the chosen provider and legal.

## Assumptions to confirm
| Assumption | Owner |
|---|---|
| `UNKNOWN` resolved within 1 hour | Finance |
| 500,000 accounts on the busiest day | Marketing |
| 5% soft declines | Payments (provider data) |
| First attempts within 3 hours | Finance |
| Export by 06:00 day one | Finance |
| 30 days for deletion; retention periods | Legal |
| 30 minutes for key rotation | Security |
| < 1 false alarm a week | On-call |
| One-month test < 2 minutes | Engineering |
| Mismatches with the provider detected within 24 hours | Finance |
| Share of renewals needing the customer; 1 hour to reach them | Payments, product |
| Retention period of 30 days for finished work | Operations, legal |
