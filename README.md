# Tally

Tally is a subscription billing system, built as a learning project and modelled loosely on [Kill Bill](https://github.com/killbill/killbill). It opens accounts, sells monthly plans, issues invoices on each account's local billing calendar, collects payment through a card provider, and grants access.

The design comes first and lives in [`docs/architecture`](docs/architecture). The code implements it, and reports back where reality differs. If you are a coding agent, start with [`CLAUDE.md`](CLAUDE.md).

## Repository layout

| Path | What |
|---|---|
| `tally/` | The application: one Spring Boot modular monolith |
| `fake-provider/` | A stand-in for the card payment provider, used by every test. [`CONTRACT.md`](fake-provider/CONTRACT.md) lists what it assumes about a real provider |
| `infra/` | `docker-compose.yml` with a local PostgreSQL |
| `docs/architecture/` | The second design: [decision log](docs/architecture/v2/README.md), [drivers](docs/architecture/v2/drivers.md) and [design summary](docs/architecture/v2/design.md). The first run's records are archived under [`archive/first-run`](docs/architecture/archive/first-run/README.md) |
| `docs/briefs/` | Each slice's brief, report, spike findings, fitness-function proof and review |
| `.github/workflows/` | CI (two stages) and the nightly load run |

## Modules

One top-level package per module under `com.tally`, with dependencies declared in each `package-info.java` and pinned in [`module-dependencies.txt`](tally/src/test/resources/architecture/module-dependencies.txt) (FR-040).

| Module | Owns |
|---|---|
| `shared` | `Money` (minor units plus ISO currency) and `DomainEvent` |
| `platform` | Durable queues, periodic jobs, retention, the event-listener executor and health metrics. No business logic (FR-027) |
| `account` | Accounts, their currency, time zone and payment token |
| `catalog` | Plans and prices |
| `subscription` | Subscriptions and their changes |
| `invoicing` | The billing calendar, invoices, and the invoiced-versus-collected check |
| `payment` | Collection requests, payment attempts, provider calls and reconciliation. Knows nothing about invoices (FR-014) |
| `entitlement` | Access granted to accounts |
| `checkout` | A process module: record the card token, subscribe, wait briefly for the first payment (FR-034) |

Each module owns one PostgreSQL schema; modules talk through public APIs and events (Spring Modulith), never each other's tables.

## Stack

Java 25, Spring Boot 4.1, Spring Modulith 2.1, PostgreSQL 17, Flyway, Testcontainers, ArchUnit, Resilience4j core, Maven wrapper (FR-039).

## Build and test

You need Java 25 and a running Docker daemon (the tests start PostgreSQL and the fake provider in containers).

```sh
./mvnw verify
```

This runs everything: unit tests, fitness functions, module integration tests, the acceptance tests AT-1 to AT-12 and the spike tests. It takes about 8 minutes.

To run one stage of the pipeline only:

```sh
./mvnw -P commit-stage verify       # unit, fitness and module tests (a few minutes)
./mvnw -P acceptance-stage verify   # AT-1 to AT-12 and the spikes
```

## Run locally

```sh
docker compose -f infra/docker-compose.yml up -d     # PostgreSQL on localhost:5432
./mvnw -pl fake-provider spring-boot:run              # fake provider on localhost:8081
./mvnw -pl tally spring-boot:run                      # Tally on localhost:8080
```

Flyway creates the schemas on start-up. A walk through the API:

```sh
# Open an account
curl -s -X POST localhost:8080/v1/accounts -H 'Content-Type: application/json' \
     -d '{"currency":"USD","timeZone":"America/New_York"}'

# Get a card token from the fake provider (it stands in for the provider's hosted card form)
curl -s -X POST localhost:8081/tokens

# Check out: record the token, subscribe, wait up to 3 s for the first payment
curl -s -X POST localhost:8080/v1/subscriptions -H 'Content-Type: application/json' \
     -d '{"accountId":"<account id>","planId":"BASIC","paymentToken":"<token>"}'

# Follow up
curl -s localhost:8080/v1/payments/<payment reference>/status
curl -s localhost:8080/v1/accounts/<account id>/access
```

Plans are `BASIC`, `STANDARD` and `PREMIUM`, priced in USD and AED. The fake provider's behaviour can be switched for experiments, for example `curl -X POST localhost:8081/admin/mode -H 'Content-Type: application/json' -d '{"modes":["OUTAGE"]}'`; see [`CONTRACT.md`](fake-provider/CONTRACT.md) for every mode.

Health of every queue, job and listener pool is published as Micrometer metrics under `tally.*`, at `/actuator/metrics`.

## Pipeline

[`ci.yml`](.github/workflows/ci.yml) runs on every push and pull request (FR-038):

1. **Commit stage:** unit, fitness and module tests; on pushes, it builds the image `ghcr.io/<owner>/tally:<commit>`.
2. **Acceptance stage:** AT-1 to AT-12 and the spikes; on pushes, it tags the image `accepted-<commit>`. Only accepted images may be deployed.

[`nightly.yml`](.github/workflows/nightly.yml) runs AT-9 and AT-10 at a larger size every night at 02:17 UTC.

## How work is done

`main` is the trunk. Every change goes through a short-lived branch and a pull request, with `./mvnw verify` green before each commit. The rules that are never broken, and when to stop and ask, are in [`CLAUDE.md`](CLAUDE.md). Each slice ends with a report in `docs/briefs/`; the latest are [`slice-1-report.md`](docs/briefs/slice-1-report.md), [`slice-1-review.md`](docs/briefs/slice-1-review.md) and [`slice-1c-report.md`](docs/briefs/slice-1c-report.md).
