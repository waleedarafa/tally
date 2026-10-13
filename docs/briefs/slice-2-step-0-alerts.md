# Slice 2, step 0 (close): alert rules

## Goal

Tally already measures its own health. Every queue, job, listener pool and payment state has a metric (FR-023). Two runbooks say what a person does when something goes wrong. But nothing connects the two. No metric turns into an alert, so no person is ever told.

Example: the billing run stalls at 02:00. `tally.job.overdue` becomes 1. Nobody looks at `/actuator/metrics` at 02:00. D9 says on-call is alerted within 5 minutes. Today nobody is alerted at all.

This step closes that gap. When it is done, every condition a person must act on has an alert rule, every alert names a runbook, and a test proves each alert fires when it should and stays quiet when it should.

## Decision to make first: where alert rules live

Three options.

| Option | What it means | Good | Cost |
|---|---|---|---|
| **A. Prometheus alerting rules in the repository, unit-tested with `promtool`** (recommended) | Tally exports its metrics in Prometheus format. A rules file says, for each alert, which metric, which threshold and for how long. `promtool test rules` replays invented metric values and asserts which alerts fire, and when. | Vendor-neutral: Prometheus, Grafana Mimir, Thanos and most hosted services read the same rules format. Testable in CI with no running system. | One new runtime dependency (the Prometheus registry). The rules are only evaluated once a Prometheus-compatible server scrapes Tally, which needs a deployed environment. |
| B. Alerts evaluated inside Tally | A Tally job checks its own metrics and sends a message. | No new tool. | A process cannot report that it is down or hung, which is the most important alert. It also mixes operations code into the application. |
| C. A vendor's monitors as code (Datadog, New Relic and so on) | Monitors declared in the vendor's format. | Rich features. | No vendor is chosen. Choosing one now would decide a platform question this step does not need to decide. |

Build A. Record it as **proposed ADR-030, "Alert rules as code"**: Prometheus alerting rules in the repository; every rule names a severity, a driver and a runbook; every rule has a `promtool` unit test; a fitness function checks rules, runbooks and metric names against each other. Where the rules are evaluated and who is paged (Alertmanager routing, a paging tool) is decided with the first deployed environment, not here.

## Scope

### 1. Export metrics in Prometheus format

- Add `io.micrometer:micrometer-registry-prometheus`. Its version must come from Spring Boot's dependency management. Check Spring Boot 4's documentation for the right artifact and endpoint; do not guess (CLAUDE.md, "Stack").
- Expose `/actuator/prometheus` beside `health` and `metrics`.

### 2. Close the metric gap the drivers reveal

D2 says an unknown payment outcome is resolved within 1 hour. No metric shows how old the oldest unknown attempt is: `tally.payment.unknown_past_key_window` only covers attempts past 20 hours. Add a gauge, `tally.payment.unknown.oldest_age_seconds`: the age of the oldest attempt in state UNKNOWN, 0 if none. Test it first.

If you find another driver measure with no metric behind it, list it in the report. Do not add metrics beyond this one without asking.

### 3. The rules file

`ops/alerts/tally.rules.yml`. Every rule carries:
- labels: `severity` (`page` or `ticket`) and `driver` (for example `D9`);
- annotations: `summary` (one sentence a person can act on) and `runbook_url` (a link to the runbook on GitHub's main branch; the repository is public).

The alerts, with the measure each one protects:

| Alert | Fires when | For | Severity | Driver | Runbook |
|---|---|---|---|---|---|
| `TallyDown` | Prometheus cannot scrape Tally (`up == 0`, or the target is absent) | 2m | page | G9 | `tally-down.md` (new) |
| `JobStalled` | `tally.job.overdue` is 1 for any job | see the budget below | page | D9 | `job-or-queue-stalled.md` (new) |
| `QueueStalled` | `tally.queue.overdue` is 1 for any queue | see the budget below | page | D9 | `job-or-queue-stalled.md` |
| `PaymentRequestedStuck` | `tally.payment.requested.stuck` is 1 | see the budget below | page | D9 | `job-or-queue-stalled.md` |
| `QueueItemsFailed` | `tally.queue.failed` > 0 for any queue | 10m | ticket | FR-017 | `queue-items-failed.md` (new) |
| `EventsStuck` | `tally.events.oldest_incomplete_age_seconds` > 900 | 0m | page | D3 | `events-stuck.md` (new) |
| `EventUnreadable` | `tally.events.unreadable` > 0 | 0m | page | D3 | `unreadable-event-publication.md` (exists) |
| `ListenerFailing` | `tally.events.listeners.failed` increased in the last 15 minutes | 0m | ticket | D3 | `events-stuck.md` |
| `PaymentUnknownTooLong` | `tally.payment.unknown.oldest_age_seconds` > 3600 | 0m | page | D2 | `unknown-payment.md` (new) |
| `PaymentUnknownPastKeyWindow` | `tally.payment.unknown_past_key_window` > 0 | 0m | page | D2 | `unknown-payment-past-key-window.md` (exists) |
| `ProviderBreakerOpen` | `tally.payment.breaker.state` is 1 (open) | 10m | ticket | ADR-013 | `provider-outage.md` (new) |
| `ProviderFalseDecline` | `tally.payment.outcome_corrected` from DECLINED to SUCCEEDED increased in the last day | 0m | ticket | D2 | `provider-false-decline.md` (new) |
| `InvariantViolated` | `tally.invariant.violations` > 0 for any check | 0m | page | D1, D2 | `invariant-violated.md` (new) |

**Metric names.** The names above are Micrometer's. In Prometheus format they become underscored, counters gain `_total`, and a base unit may be added. Use the names Tally actually exports. The fitness function in point 6 decides; where it disagrees with this table, correct the table and the rules, not the test.

**The D9 budget.** D9 says a stalled billing run alerts on-call within 5 minutes. The time from stall to alert is: the job's or queue's own overdue threshold, plus up to one scrape interval (assume 30 seconds), plus the rule's `for`. For every job and queue that does billing or payment work, that sum must be 5 minutes or less. Choose each `for` to meet it, and keep it at least 1 minute so a single bad scrape does not page anyone. If a job's overdue threshold makes 5 minutes impossible, stop and report it: CLAUDE.md says a response measure that cannot be met is a stop-and-ask.

### 4. Runbooks

One runbook per alert in the table that names a new file, in `docs/runbooks/`, in the format of the two that exist: Who, When (the alert and what it means), how to see what is wrong (queries, log lines), What to do by cause, and what never to do. Keep each short. A runbook names commands and queries a person can run, not general advice.

Each existing metric description that already points to a runbook keeps its link.

### 5. Unit tests for every rule

`ops/alerts/tally.rules.test.yml`, run with `promtool test rules`. For every alert:
- one test where it fires, at the expected time;
- one test just below its threshold or `for`, where it does not fire.

For `JobStalled` and `QueueStalled`, the test shows the D9 budget: a stall that starts at time zero fires by 5 minutes, using the real overdue threshold of the billing run's job and queue.

### 6. Fitness function F14: rules, runbooks and metrics agree

A test that fails when:
- a rule has no `severity`, `driver`, `summary` or `runbook_url`;
- a `runbook_url` points to a file that does not exist in `docs/runbooks/`;
- a runbook in `docs/runbooks/` is named by no rule;
- a rule names a metric that Tally does not export. Start Tally in a test, scrape `/actuator/prometheus`, and compare. **This is the important one.** An alert on a renamed metric never fires, and nothing shows it.
- a `page` alert has no unit test in the test file.

Show F14 failing on each of these with a deliberate mistake, then green.

### 7. CI

The commit stage runs `promtool check rules` and `promtool test rules`. Use the official Prometheus container image, pinned to an exact version you have checked exists. Do not install Prometheus on the runner by hand.

## Out of scope

- Where rules are evaluated, Alertmanager, routing, receivers and the paging tool. These need a deployed environment and are decided with it.
- Dashboards.
- Scrape configuration for a real environment.
- Alerts for Access. Access becomes its own deployable later in Slice 2, and its alerts come with it.

## Acceptance checks

| Check | Pass when |
|---|---|
| A0 | `/actuator/prometheus` serves every `tally.*` metric |
| A1 | `tally.payment.unknown.oldest_age_seconds` shows the oldest UNKNOWN attempt's age, and 0 when there is none |
| A2 | `promtool check rules` passes |
| A3 | Every alert has a firing test and a quiet test; `promtool test rules` passes |
| A4 | The D9 budget test passes for the billing run's job and queue |
| A5 | F14 passes, and was shown failing on each of its five conditions |
| A6 | Every new runbook exists and is named by a rule |
| A7 | CI's commit stage runs A2 and A3 |

## Pull requests

1. **Export and the missing metric:** points 1 and 2, with A0 and A1.
2. **Rules, tests, runbooks and F14:** points 3 to 6, with A2 to A6.
3. **CI:** point 7, with A7.

Before acting on any decision I paste, post it verbatim on the PR it concerns (CLAUDE.md).

## Definition of done

- The three PRs are merged, and CI is green on main.
- `docs/briefs/slice-2-step-0-report.md` closes step 0. It covers:
  - the test-isolation work (a short summary that links the spike report and the rollout);
  - this step, per acceptance check, pass or fail;
  - every alert with its measured stall-to-alert time where the driver gives one;
  - any driver measure still without a metric or an alert;
  - proposed ADR-030 as built.

## Stop and ask, instead of deciding, when

- A driver's measure cannot be met by any `for` value (the D9 budget above).
- The Prometheus registry needs a version Spring Boot does not manage.
- An alert would need a metric that does not exist, other than the one in point 2.
- A runbook would need a command Tally does not have. Say what is missing, as the unknown-payment runbook did for recording a decision.
