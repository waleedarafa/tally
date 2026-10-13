# Slice 2, step 0: report

Step 0 had two parts: test isolation, then alert rules. This report closes it, as the alerts brief's definition of done asks (`docs/briefs/slice-2-step-0-alerts.md`). Decisions were posted verbatim on the pull request each concerns. Every result below comes from a test or a CI run named beside it.

## 1. Test isolation (summary)

- **Spike:** `docs/briefs/slice-2-spike-test-isolation.md` (#37). Five options were compared. The recommendation was B2, a PostgreSQL database per test class, copied from a template migrated once per run. The spike also corrected the brief's premise: cached test contexts never ran their job runners at the same time. The interference came from rows left behind on another context's clock, a context's own runners, and instances started by hand.
- **Fix found on the way:** `QueuePoller.stop()` could lose a claimed item and a worker permit (#38).
- **Rollout:** #40 (`docs/briefs/slice-2-test-isolation-rollout/`).
  - `PerContextDatabase` gives every test class its own database. Every patch that rewrote job rows is removed.
  - F13 (`F13NoTestMakesWorkDueTest`) forbids a test outside `com.tally.platform` to rewrite a job or queue row's due time or status. It has no exceptions: a queue fault goes through the platform's `QueueFaults`.
  - Measured: 0 failures in 10 full runs, median 422.5 s (`results/rollout-2`).
- **Decision:** ADR-029 was accepted after the rollout (#41), with drivers D10 and the suite's reliability. Its checks are F13 and `PerContextDatabase`.

## 2. Alert rules: acceptance checks

| Check | Result | Evidence |
|---|---|---|
| A0 `/actuator/prometheus` serves every `tally.*` metric | **Pass** | `PrometheusEndpointIT.everyTallyMetricIsServedInPrometheusFormat` (#46) |
| A1 `tally.payment.unknown.oldest_age_seconds`: oldest UNKNOWN attempt's age, 0 when none | **Pass** | `UnknownAgeIT` (#46); failed first with `MeterNotFoundException` |
| A1b `tally.queue.last_progress_age_seconds` resets when an item finishes and ages while nothing finishes | **Pass** | `HealthMetricsIT.lastProgressResetsWhenAnItemFinishesAndAgesWhileNothingFinishes` (#46); failed first |
| A2 `promtool check rules` passes | **Pass** | 16 rules (#48); CI step "Alert rules" (#50) |
| A3 every alert has a firing and a quiet test; `promtool test rules` passes | **Pass** | `ops/alerts/tally.rules.test.yml` (#48); CI step "Alert rules" (#50) |
| A4 D9 budget: a stall fires within 4m30s; a busy run never fires | **Pass** | `BillingStalled` and `ListenersStalled` cases in the rule tests (#48) |
| A5 F14 passes, and was shown failing on each of its five conditions | **Pass** | `F14AlertRulesAgreeIT`; the five deliberate mistakes are on #48 |
| A6 every new runbook exists and is named by a rule | **Pass** | Nine runbooks (#48); F14's conditions 2 and 3 check it |
| A7 CI's commit stage runs A2 and A3 | **Pass** | `.github/workflows/ci.yml`, step "Alert rules" (#50). Its first run, on da6a379, printed "SUCCESS: 16 rules found" and the rule tests' "SUCCESS": [commit-stage job](https://github.com/waleedarafa/tally/actions/runs/38400060978/job/115305076104) |

## 3. Two corrections made during review

- **D9 is measured as progress, not as age (#45).**
  - The first brief alerted on `tally.queue.overdue` and `tally.job.overdue`. Both have a 5-minute floor (FR-023), so no alert could reach on-call within D9's 5 minutes; the threshold check stopped there and asked.
  - Lowering the floor would not have fixed it. On D6's busiest day the oldest due renewal is legitimately hours old, so any age threshold would page on every busy billing day, and a real stall would look the same as a busy one.
  - The amended brief asks two questions instead. D9 asks whether work is due and nothing finishes (`BillingStalled`, `ListenersStalled`). D6 asks whether the oldest due item is older than 4 hours (`BillingBehind`).
  - This needed a new metric, `tally.queue.last_progress_age_seconds` (A1b).
  - The review of #46 added the second clock to `BillingStalled`. Progress alone would page when one item falls due on a queue idle for hours.
- **`ListenersStalled` reads one instance's pool (#48).**
  - The review of #46 had given it the same two clocks, with the oldest incomplete publication as the second.
  - The review of #48 corrected that, for two reasons:
    - A failed publication waits outside the pool for resubmission after 5 minutes (AT-8 is a normal business case), so a quiet night would page for work resubmission fixes by itself.
    - That age is read from the database for every instance, while the last success is per instance, so a quiet instance would page for another instance's slow work.
  - The rule is now: listener work queued and no success, both from the same instance's pool. Two clocks apply to queues, where a failed item comes back due.

## 4. Every alert, and its time from fault to alert

Times are from the rule unit tests. Those sample every 30 s, the scrape interval the brief assumes, so a time is the threshold, plus at most one scrape, plus the rule's `for`. "Driver measure" quotes `docs/architecture/v2/drivers.md` where the driver gives one.

| Alert | Severity | Driver | Fault to alert | Driver measure |
|---|---|---|---|---|
| `TallyDown` | page | G9 | scrape fails, then 2m (fires at 3m when the first failed scrape is at 1m) | none given |
| `BillingStalled` | page | D9 | **4m30s** after work is due and nothing finishes | on-call alerted within 5 minutes |
| `ListenersStalled` | page | D9 | **4m30s** after work is queued and no listener succeeds | on-call alerted within 5 minutes |
| `BillingBehind` | page | D6 | oldest due item past 4 h, plus at most one scrape | busiest day within 4 hours |
| `JobOverdue` | ticket | FR-023 | the job's overdue threshold (twice its interval, at least 5 minutes), plus 5m | FR-023 |
| `PaymentRequestedStuck` | page | D2 | 15 minutes waiting to be sent, plus at most one scrape | zero double charges |
| `MoneyQueueItemsFailed` | page | D1, D2 | the first failed item, plus at most one scrape | zero duplicates and misses; zero double charges |
| `QueueItemsFailed` | ticket | FR-017 | 10 minutes after the first failed item | FR-017 |
| `EventsStuck` | page | D3 | publication 15 minutes old, plus at most one scrape | none pending longer than 15 minutes |
| `EventUnreadable` | page | D3 | the first unreadable publication, plus at most one scrape | D3 |
| `ListenerFailing` | ticket | D3 | the first listener failure, plus at most one scrape | D3 |
| `PaymentUnknownTooLong` | page | D2 | oldest UNKNOWN past 50 minutes, plus at most one scrape (changed by ADR-031, #52) | resolved within 1 hour |
| `PaymentUnknownPastKeyWindow` | page | D2 | the first attempt past the key window (20 h) | D2 |
| `ProviderBreakerOpen` | ticket | ADR-013 | 10 minutes without the breaker closing | ADR-013 |
| `ProviderFalseDecline` | ticket | D2 | the first DECLINED→SUCCEEDED correction, plus at most one scrape | D2 |
| `InvariantViolated` | page | D1, D2 | the daily check's run, plus at most one scrape | D1: zero duplicates; D2: zero double charges |

**`EventsStuck` alerts at its measure, not before it.** D3 allows no publication pending beyond 15 minutes; the warning before that is `ListenerFailing`, a ticket on the first failure (decision on #50). **`PaymentUnknownTooLong` alerts at 50 minutes, before D2's hour ends** (ADR-031, #52): the reconciliation schedule now checks an UNKNOWN attempt at 5, 15, 30, 45 and 60 minutes, so one failed check no longer loses the hour, and a person is paged after the fourth.

## 5. Driver measures still without a metric or an alert

| Driver | Measure | What exists | Gap |
|---|---|---|---|
| D1 | zero misses across renewals | `InvariantViolated` catches duplicates; `BillingStalled` and `MoneyQueueItemsFailed` catch a stopped or failing run | no check that every period due was renewed |
| D6 | the busiest day done within 4 hours | `BillingBehind`: the oldest due item older than 4 hours | a proxy: it measures lateness, not the run's total time |
| D8 | personal data gone within 30 days | nothing | deletion is not built yet |
| D13 | "Can I watch?" answered 99.9% of the month | nothing | Access becomes a deployable later in Slice 2; its alerts come with it (brief, out of scope) |
| D14 | every payment mismatch detected within 24 hours | nothing | settlement reconciliation is ADR-021, proposed and required before production |
| D15 | an authentication demand reaches the customer within 1 hour | nothing | ADR-023, proposed |

D4, D5, D7, D10, D11 and D12 measure a change, an audit or a test, not a running system, so they need no runtime alert.

## 6. Findings for routing and later decisions

Recorded as the reviews of #46 and #48 asked: (a) to (d) are items 4a to 4d of the #48 review; (e) and (f) come from the review of #46 and item 3 of #48's.

- **(a) A provider outage pages through `BillingStalled` at 4m30s.**
  - While the provider breaker is open, `payment-collection` claims nothing, so its work is due and nothing finishes.
  - That page comes before `ProviderBreakerOpen`'s 10-minute ticket.
  - When routing is decided, `ProviderBreakerOpen` should suppress `BillingStalled{queue="payment-collection"}`. `billing-stalled.md` already sends the reader to `provider-outage.md` for that cause.
- **(b) One fault raises one alert per instance.**
  - Many gauges are read from the database, so every instance reports the same value: queue ages and counts, job ages, events, payments and invariants.
  - Routing should group by `alertname` and by `queue` or `check`, never by `instance`.
  - The exception is `ListenersStalled`, which is per instance on purpose.
- **(c) A queue where every item is sent back is caught by `PaymentRequestedStuck` after 15 minutes.**
  - An item sent back to the queue counts as progress, so `BillingStalled` does not see this case.
  - The only path that sends an item back is `CollectionWorker`'s held-back branch: Tally's own rate permit says wait. The attempt stays REQUESTED, and `PaymentRequestedStuck` fires once one has waited 15 minutes.
  - A technical failure is not sent back. It finishes the item and queues a new attempt. If those failures persist, the breaker opens.
- **(d) `invariant-violated.md` needs commands Tally does not have: refund a charge, and void an invoice.**
  - Until they exist, the runbook uses the provider's dashboard and a reviewed database change.
  - Open question, beside ADR-022 (what happens to an invoice and to access when a recorded success proves false): who may refund or void, and through which command.
- **(e) `last_progress_age_seconds` differs per instance when no finished row exists.**
  - When no finished row is left (a new database, or after retention deletes every finished row), the gauge counts from when this instance registered the queue. So it differs per instance and resets when an instance restarts.
  - `BillingStalled` also requires work due for 180 s, so the fallback alone never pages.
- **(f) The scrape configuration must match two assumptions** recorded in the rules file header: the scrape job is named `tally`, and `honor_labels` is false, so Tally's job tag arrives as `exported_job`.

## 7. Proposed ADR-030, as built

- **Rules:** `ops/alerts/tally.rules.yml`, 16 Prometheus alerting rules. Each has a `severity` (`page` or `ticket`), a `driver`, a `summary`, and a `runbook_url` that points to `docs/runbooks/` on `main`.
- **Unit tests:** `ops/alerts/tally.rules.test.yml`, run with `promtool test rules`. Every alert has a test where it fires and one where it stays quiet. The D9 budget is tested for both stall rules.
- **Runbooks:** eleven, nine of them new in this step.
- **F14 (`F14AlertRulesAgreeIT`):** it starts Tally and scrapes it, then fails on any of five conditions:
  - a rule without its severity, driver, summary or runbook;
  - a runbook URL that names no file;
  - a runbook no rule names;
  - a rule that reads a metric Tally does not export;
  - a page alert without both a firing and a quiet test.
- **CI:** the commit stage runs `promtool check rules` and `promtool test rules` from `prom/prometheus:v3.15.0`, pinned by digest.
- **D9 is measured as progress, not as age.**
  - `BillingStalled` uses two clocks on the money queues (`money="true"`): nothing finished and work due, each over 180 s, for 1m.
  - `ListenersStalled` reads one instance's listener pool: work queued and no success, over 180 s, for 1m.
  - D6 is `BillingBehind`.
- **Not decided here:** where the rules are evaluated, Alertmanager, routing and paging. They come with the first deployed environment (section 6 lists what routing must do).

**Metrics added:**
- `tally.payment.unknown.oldest_age_seconds` (A1);
- `tally.queue.last_progress_age_seconds{queue, money}` (A1b), read from a new `finished_at` column on every queue table (V29);
- `tally.payment.outcome_corrected{from,to}` is now registered at startup for each pair `Corrections` records. That is not a new metric, but `increase()` needs the series at 0 before its first step.

## 8. Fitness functions added in step 0

- **F13 (`F13NoTestMakesWorkDueTest`):** no test outside the platform rewrites a job or queue row's due time or status (ADR-029).
- **F14 (`F14AlertRulesAgreeIT`):** rules, runbooks, rule tests and exported metric names agree (ADR-030).

## 9. Audit of the decision records step 0 touched

| Record | Its check | Exists in the code? |
|---|---|---|
| ADR-029 (test isolation) | F13; `PerContextDatabase` | Yes: `F13NoTestMakesWorkDueTest`, `support/PerContextDatabase` |
| ADR-030 (alert rules as code) | `promtool check rules` and `test rules` in CI; F14 | Yes: CI step "Alert rules"; `F14AlertRulesAgreeIT` |
| ADR-013 (provider breaker) | `ProviderBreakerOpen` and its tests | Yes |
| ADR-018 (stale REQUESTED attempt) | `PaymentRequestedStuck` and its tests | Yes |
| FR-023 (job health) | `JobOverdue`; `EveryJobHasHealthMetricsIT` | Yes |

**Gaps:**
- No rule is evaluated anywhere yet. Every check above proves the rules are right; none proves an alert reaches a person. That is the deployed environment's work.
- ADR-021 (settlement reconciliation) and ADR-023 (authentication demand) have no alert, because they have no implementation (section 5).
