# Slice 4 brief: Authentication

Status of this brief: **draft**. One decision below waits for the user. Do not start PR-2 before it is confirmed on the pull request of this brief.

## Goal

Today anybody who can reach the API can act as any household.

Example: Sara's account ID is in the URL of her app. Omar copies it. He calls `GET /v1/accounts/{Sara's ID}` and reads her account. He calls "pay now" for her, and Tally charges her card. Tally cannot tell the two people apart, because no request says who sends it.

Decision 032 gives the answer: every request carries a signed token from an identity provider, and Tally checks it.

When this slice is done:

- A request to billing with no valid token gets 401.
- A user can act only for the user's own account. A request for another household's account, subscription or payment gets 404, and nothing changes.
- One user has one account (WAR-84, decided 13 November). A second "open an account" from the same user gives the account that the user has.
- Idempotency keys belong to the caller, not to the account (ADR-027).
- Access answers only a caller that has the right token (decision 1 below).
- All acceptance tests of Slices 1 to 3 still pass, now with tokens.

## Why this slice comes next

- Decision 032 is "required before production". So is a person's decision on an unknown payment (WAR-50), and that needs a staff token. This slice is the base for it.
- Slice 3 added "pay now". Today anybody can make Tally charge any household's card.
- No decision blocks it. WAR-84 settled the last open question.

## Read first

1. `docs/architecture/v2/README.md`: decisions 019, 027, 032, 033, 034 and 036.
2. Linear WAR-49 (the plan) and WAR-84 (one account for each user, option A).
3. `docs/architecture/v2/design.md` §8 (the REST contract) and §3 (the module graph).
4. `docs/architecture/v2/design-record.md`: the Wardley map rows "Access granting" and "Identity and login"; "Required before production".
5. `docs/api/openapi.yaml` and `docs/api/access-openapi.yaml`.
6. killbill-wiki: how Kill Bill authenticates its callers. Use it to compare, not to copy.

## What exists today (checked against `main` at 41556ea)

- No security dependency in `tally/pom.xml` or `access/pom.xml`. Every endpoint is open.
- Five billing operations: `openAccount`, `getAccount`, `signUp`, `payNow`, `getPaymentStatus`. One Access operation: `getAccess`.
- `signUp` names the account in its body (`accountId`). `getAccount` and `payNow` name it in the path. `getPaymentStatus` names a payment reference.
- `PaymentStatus` has no account ID. `PaymentApi.requestCollection` receives one, so Payment knows it.
- `payment` depends only on `shared` and `platform` (the pinned graph).
- Idempotency keys are scoped by account. For `openAccount`, all callers share one scope (design.md §8).
- The actuator endpoints (health, readiness, metrics) are on the same server as the API.

## The decision that waits for the user

### 1. Who calls Access, and with which token?

Billing's link between a user and an account lives in billing. Access does not have it. So Access cannot check a household's own token against the account in the request.

The Design Record says Access "ties into our own streaming platform and each telecom partner" (Wardley map, "Access granting").

| Option | What happens | Cost |
|---|---|---|
| **A. The streaming platform calls Access with a service token (recommended)** | The household's app talks to the streaming platform. The platform knows the household's account and asks Access with its own service token. Access checks the token's audience and scope. Access refuses a household's token. | The streaming platform must keep the account ID of each user. It learns it once, from billing's new `GET /v1/me/account`. |
| B. The app calls Access with the household's token | Billing adds the user ID to the access facts. Access checks that the token's user holds the account. | A change of contract v1 (an added field). The user ID goes into the event log, so F15's rule "no personal data" needs a new reading: the user ID is an opaque number, like the account ID. One more fact that can be late: a new user's first answer is `NOT_KNOWN` until the link arrives. |
| C. Leave Access open in this slice | Nothing changes in Access. | Anybody can ask "may this account watch?" for any account ID. The IDs are random UUIDs, so guessing is hard, but a leaked ID answers. Not acceptable before production. |

Option A matches the Design Record, keeps contract v1 as it is, and asks no new privacy question. Telecom partners (later) get service tokens the same way.

## Rules of the design (not open)

These follow from 032, 027 and WAR-84.

1. **Tally checks every token itself.** It checks the signature with the identity provider's public keys, the issuer, the audience and the expiry. It does not call the identity provider for each request.
2. **The token's subject (`sub`) is the user ID.** Tally keeps no name and no email. The identity provider knows nothing about accounts.
3. **The link lives in `account`.** A table that holds one row for each user, with a named unique constraint on the user ID. One user, one account (WAR-84).
4. **"Open an account" is safe twice.** A second request from the same user, with any key, answers 200 with the account that the user has. Two requests at the same time give one account: the loser's transaction fails on the named constraint, rolls back, and reads the winner's account. Any other failure is a failure (rule 11).
5. **A request for another household gets 404, never 403.** A 403 would say "this account exists". Nothing changes.
6. **The caller's account is found once for each request.** The `account` module finds it from the user ID and puts it where every module's web layer can read it, through a type in `platform`. No module gets a new dependency. `platform` holds the type, and no rule about accounts.
7. **Payment answers only for its own reference's account.** `PaymentStatus` gains the account ID (an added field). The controller compares it with the caller's account.
8. **Idempotency keys are scoped per caller** (ADR-027). The scope is the user ID for a household, the client ID for a service.
9. **Time comes from the injected `Clock`** (rule 4). The expiry check uses it, so tests can move the clock past a token's end.
10. **The health and metrics endpoints stay open** to the platform that runs Tally, on a separate management port. They hold no household data.
11. **No identity provider product is chosen in this slice.** Tally reads the issuer, the audience and the key location from settings. Tests make their own tokens with a test key. The choice of product comes with the production operations slice.

## Target shape

```
household app ──token (sub = user)──► billing ──► account: user → account (one row per user)
                                         │
                                         └─ every controller: "is this the caller's account?" → else 404

streaming platform ──service token (aud = tally-access)──► Access      (decision 1, option A)

identity provider ── public keys (fetched and kept) ──► billing, Access
```

| Module | Change |
|---|---|
| `platform` | The type that holds the caller (user or service) and, for a user, the account. Idempotency keys scoped by caller. |
| `account` | The link table. "Open an account" links the caller, once. The step that finds the caller's account. `GET /v1/me/account`. |
| `subscription`, `collections` | Their controllers check the account in the request against the caller's. |
| `payment` | `PaymentStatus.accountId` (added). Its controller checks it. |
| Access (`access/`) | Requires a service token (option A). No change to `contracts/access-facts/`. |

## New endpoint: `GET /v1/me/account`

- 200 with the caller's account, or 404 if the user has none yet.
- It lets an app on a new phone, and the streaming platform, find the account without a stored ID.
- Read only: no idempotency key.

## In scope

- Everything in "Target shape".
- `openapi.yaml` and `access-openapi.yaml`: the security scheme, 401 on every operation, `GET /v1/me/account`.
- A test token issuer for every test: a test key pair, tokens with a chosen user, audience, scope and expiry.
- Every existing test sends a token. Acceptance tests use one user for each household.
- Metrics and an alert rule for a surge of 401 answers and for keys that cannot be fetched (ADR-030), with runbooks.
- The documents: decision 032 with WAR-84's limit and decision 1; design.md §8; the glossary; the topic tracker (I-02, I-04).

## Out of scope

- Login, passwords, sign-up of a user. The identity provider does that.
- Choice of identity provider product.
- Roles for staff, and any back-office endpoint (WAR-50 comes later, with the first staff role).
- Telecom partners' tokens.
- Rate limits.
- Moving an account to another user, or two users for one household.

## Spikes, before PR-2

Write the findings in `docs/briefs/slice-4-spikes.md`, each with a test that pins it.

| Spike | Question |
|---|---|
| S-1 | Spring Security with Spring Boot 4.1.x: which starter or module, which version, and the settings for issuer, key location and audience. Can the expiry check use the injected `Clock`? Check the official documentation; do not guess. |
| S-2 | The identity provider is down. (a) Billing and Access are running: how long do they keep the keys, and do they still accept tokens? (b) An instance starts while the provider is down: does it start, and does it accept tokens? Access must survive this (D13). If it cannot, stop and ask. |
| S-3 | Can each controller find its account without a new module dependency? Show it for `getPaymentStatus`, the hardest case. |
| S-4 | Kill Bill check, with path:line citations: how Kill Bill authenticates a caller and limits it to its own tenant's data. Mark anything not read in the code as "inference". |

## Pull requests, in order

Write each acceptance test before its implementation, and show it red first.

### PR-0 · Spikes

S-1 to S-4, tests only, and the spikes document.

### PR-1 · Docs: adopt this brief (no code)

- `README.md`: 032 gets WAR-84's limit and decision 1. New proposed rows from 039 on, if the spikes need them.
- `design.md` §8: authentication, the 404 rule, keys per caller, `GET /v1/me/account`.
- CLAUDE.md: the current slice is `docs/briefs/slice-4.md`. A new rule 16, "every request is authenticated, and acts only for the caller's account".

### PR-2 · The test token issuer, and tokens in every test

The issuer for tests. Every test sends a token. The server does not check it yet, so the build stays green.

### PR-3 · Billing requires a token; the account link

Security on for billing. 401 without a valid token. The link table, "open an account" safe twice, `GET /v1/me/account`. AT-26 and AT-28 come with this pull request.

### PR-4 · Each request acts only for the caller's account

The caller's account for each request; the checks in each controller; `PaymentStatus.accountId`. AT-27 comes with this pull request.

### PR-5 · Idempotency keys per caller

The key tables gain the caller. The migration follows ADR-009: keys of today expire after 24 hours, so the old scope ends on its own. AT-29 comes with this pull request.

### PR-6 · Access requires a service token

As decided in decision 1. AT-30 comes with this pull request.

### PR-7 · Alerts and runbooks (ADR-030)

The rules for 401 surges and for keys that cannot be fetched, with firing tests, quiet tests and runbooks. F14 must pass.

### PR-8 · Report and documents

`docs/briefs/slice-4-report.md` with the six points of CLAUDE.md. The audit covers 019, 027, 032, 033, 034, 036 and the new rows.

## Acceptance tests

| AT | Name | Passes when |
|---|---|---|
| AT-1 to AT-25 | kept | They pass with tokens. |
| AT-26 | No valid token, no answer | For every operation in `openapi.yaml`: no token, a bad signature, an expired token (the clock moved), a wrong issuer and a wrong audience each get 401. Nothing changes. The list of operations comes from the contract, so a new endpoint is covered at once. |
| AT-27 | One household cannot act for another | Users A and B each have an account and a subscription. A asks for B's account, signs B up, presses "pay now" for B, and reads B's payment status. Each gets 404. B's data and B's card are not touched: no new attempt at the fake provider. |
| AT-28 | One account for each user | A user opens an account, then opens one again with a new key: 200, the same account. Two requests at the same time with two keys: one account. `GET /v1/me/account` gives that account. |
| AT-29 | Keys belong to the caller | Users A and B send the same key value. Each gets the result of the user's own request. |
| AT-30 | Access asks for a service token | Decision 1, option A: a service token with the right audience gets the answer; no token gets 401; a household's token gets 403. Access still answers while the identity provider is down, as far as S-2 allows. |

## Stop and ask, instead of deciding, when

- A module needs a dependency that the pinned graph does not have.
- A change is needed in `contracts/access-facts/v1/`.
- S-2 shows that Access cannot answer while the identity provider is down (D13).
- A library behaves differently from what this brief assumes.
- The test token issuer needs a new dependency. Check the official documentation first.

## Evidence used for this brief

- `main` at 41556ea: `docs/api/openapi.yaml`, `docs/api/access-openapi.yaml`, `tally/pom.xml`, `access/pom.xml`, `PaymentStatus.java`, `PaymentApi.java`, `tally/src/test/resources/architecture/module-dependencies.txt`, `design.md` §8.
- `docs/architecture/v2/README.md`: rows 027 and 032.
- `docs/architecture/v2/design-record.md`: the Wardley map ("Access granting", "Identity and login").
- Linear: WAR-49, WAR-84.
- Spring Boot reference 4.1.2, "OAuth2", resource server: the settings `issuer-uri`, `jwk-set-uri`, `public-key-location` and `audiences` exist. The page names the module `spring-security-oauth2-resource-server`. The starter name is not on that page; S-1 checks it.
- Not verified here: how Spring keeps the keys when the identity provider is down (S-2), and any statement about Kill Bill (S-4).
- The build was not run for this brief. It changes one document.
