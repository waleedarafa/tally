# Slice 4 report: Authentication

Slice 4 put a signed token from an identity provider on every request (ADR-032). Billing accepts only a household's token and acts only for that household's account. Access accepts only the streaming platform's service token (the brief's decision 1, option A). Both keep checking tokens while the identity provider is down, within the limits of ADR-039.

The slice ran from PR #85 to #93, all merged; this report is PR-8. Record numbers follow CLAUDE.md: `ADR-NNN` for the second design, `FR-NNN` for the first run, `Dn` for drivers.

**The brief's goal, and where it stands:**

| When this slice is done | State | Evidence |
|---|---|---|
| A request to billing with no valid token gets 401 | Yes | AT-26 (#89): every operation in `openapi.yaml`; no token, a bad signature, a wrong issuer, a wrong audience, a value that is not a token, and a token expired on the moved test clock |
| A user acts only for the user's own account; another household's account, subscription or payment gets 404, and nothing changes | Yes | AT-27 (#90); F16 (#91) |
| One user has one account; a second "open an account" gives the account the user has | Yes | AT-28 (#89): the race made certain |
| Idempotency keys belong to the caller | Yes | AT-29 (#91) |
| Access answers only the streaming platform's service token | Yes | AT-30 (#92) |
| All acceptance tests of Slices 1 to 3 pass, with tokens | Yes | AT-1 to AT-25 send tokens since #88, and are checked since #89 (billing) and #92 (Access). Every acceptance stage of the slice is green |

**Builds at the end of the slice** (`main` at 8b8adde, after #93):
- `./mvnw verify` on JDK 25: BUILD SUCCESS. The fake provider ran 20 tests; Access 16 unit and 26 integration tests; billing 176 unit and 283 integration tests.
- promtool: 28 rules, every unit test passing.
- CI is green on every merged head. The counts for this pull request's head are in its description.

**Documents updated:**
- In this pull request: the glossary (user, account link, another household, identity provider, a caller's token, caller, service token, key set, hold-off; an idempotency key belongs to the caller) and the topic tracker (I-02 and I-04, in the `.md` and the `.csv`).
- Updated by the pull requests that changed them: row 032 (WAR-84's limit and decision 1) and row 039 (#87); design.md §8 (#87, #89 to #92); `openapi.yaml` (#89 to #91); `access-openapi.yaml` (#92); CLAUDE.md rule 16 (#87).

**Where the user's decisions are recorded.** Each decision given in chat is posted verbatim as a comment on the pull request it concerns, before it is acted on. The architecture reviews are comments on the same pull requests, and this report cites them by pull request.

| PR | Brief item | Merged |
|---|---|---|
| #85 | The brief, with decision 1 (option A) | c65d47c |
| #86 | PR-0, the four spikes | 4a942b9 |
| #87 | PR-1, the documents adopt the brief; row 039 decided (option A for Access, option B for billing, short timeouts) | 9c4a901 |
| #88 | PR-2, the test token issuer, and tokens in every test | 390965a |
| #89 | PR-3, billing requires a token; the account link (AT-26, AT-28) | c9589d6 |
| #90 | PR-4, each request acts only for the caller's account (AT-27) | eab9a06 |
| #91 | PR-5, idempotency keys per caller (AT-29); F16 | c179724 |
| #92 | PR-6, Access requires a service token; ADR-039 option A (AT-30) | f9e234f |
| #93 | PR-7, alerts and runbooks | 8b8adde |

## 1. What was built, per acceptance test

Each test below was written first and shown red. The red result is in the description of its pull request.

| AT | Status | Test | What it shows | Result |
|---|---|---|---|---|
| AT-26 | new (#89) | `AT26NoValidTokenNoAnswerIT`: `everyOperationAnswers401ToEachKindOfBadTokenAndNothingChanges`, `anExpiredTokenAnswers401AfterTheClockMovesAndNothingChanges`, `aValidTokenIsNotRefused` | The list of operations is read from `openapi.yaml`, so a new endpoint is covered at once. For each one: no token, a bad signature, a wrong issuer, a wrong audience and a value that is not a token each get 401, and no row of a business schema changes. A token expires once the test clock moves past its end. That checks rule 4 on the expiry (spike S-1, finding 5) | pass (3) |
| AT-27 | new (#90) | `AT27OneHouseholdCannotActForAnotherIT`: `eachRequestForAnotherHouseholdAnswers404AndChangesNothing`, `anotherHouseholdsAccountAnswersAsAnAccountThatDoesNotExist` | A and B each have an account and a subscription, and B has a debt open. A asks for B's account, signs B up, presses "pay now" for B, and reads B's payment status: each answers 404. B's account and card, B's case, B's attempts and B's charges at the fake provider are unchanged, and B's own requests still answer. The answer for B's account equals the answer for a random ID | pass (2) |
| AT-28 | new (#89) | `AT28OneAccountForEachUserIT`: `aSecondOpenWithANewKeyAnswers200WithTheSameAccount`, `twoUsersGetTwoAccounts`, `twoOpensAtTheSameTimeWithTwoKeysGiveOneAccount`, `theLoserOfTheRaceRollsBackAndAnswersTheWinnersAccount` | A second open with a new key answers 200 with the same account. Two opens at the same time give one account. The race is made certain: the request waits on `account_user_one_per_user` behind an uncommitted link, rolls back, and answers the winner's account. `GET /v1/me/account` gives the account | pass (4) |
| AT-29 | new (#91) | `AT29KeysBelongToTheCallerIT`: `userBWhoSendsUserAsKeyToOpenAnAccountGetsBsOwnAccount`, `twoUsersSigningUpWithOneKeyValueEachGetTheirOwnSubscription` | User B sends the key value user A used: B gets B's own account, never A's (red first: B got A's account). Two users who sign up with one key value get two subscriptions, stored under `user:<A>` and `user:<B>` | pass (2) |
| AT-30 | new (#92) | `AT30AccessAsksForAServiceTokenIT`: `onlyTheStreamingPlatformsServiceTokenGetsTheAnswer`, `duringAnOutageAccessAnswersWhileTheServiceTokenLivesAndRefusesItAfter`, `tokensWithUnknownKeyIdsWhileTheProviderHangsDoNotHoldUpTheServiceToken`, `anInstanceThatNeverFetchedTheKeysAnswers503DuringAnOutage` | The service token answers 200. No token, another audience or a bad signature: 401. A household's token, or Access's audience without the scope: 403. **The service token lives 1 hour.** During an outage, a running instance and one started during it answer that token, with no fetch of the keys; once the clock passes the hour, both refuse it with 401. While the provider hangs, a flood of tokens with unknown key IDs gets only 401, and the service token waits less than 3 s (red first: 7,639 ms; green: 1,578 ms behind the one fetch under way, then 19 to 33 ms). An instance with no key set kept answers 503 during an outage | pass (4) |
| AT-18 | changed (#89) | `AT18AccessSurvivesBillingIT` | Its separate billing instances check tokens with the test key set (#88 review). The test first shows instance A refusing a forged token | pass |
| AT-1 to AT-25 | kept | as in the Slice 3 report | Each sends one household's token per account (#88). The helper that asks Access sends the service token | pass |

## 2. Spike findings

The four spikes are in `docs/briefs/slice-4-spikes.md`, each pinned by a test or by a citation (#86).

| Spike | Finding | What the slice did |
|---|---|---|
| S-1 | The starter is `spring-boot-starter-security-oauth2-resource-server` 4.1.1; Boot 3's name does not exist for 4.1.1. The expiry check reads the system clock unless one `JwtTimestampValidator` bean is given the injected `Clock`. The `typ` header must be `JWT` or absent (`SpikeAuthS1TokenChecksTest`) | Billing (#89) and Access (#92) each have the starter and the one bean. AT-26 and AT-30 move the clock past a token's end. The `typ` finding is an open question for the choice of identity provider (§4.3) |
| S-2 | With Boot's defaults, a running instance keeps the keys 5 minutes and then refuses every token, and an instance that starts during an outage refuses every token. A provider that hangs holds each request up to 30 s, behind one lock (`SpikeAuthS2ProviderDownTest`) | This was the brief's stop condition. The user decided 039 on #87: option A for Access, option B for billing, short timeouts for both. Built in #89 and #92; the hold-off was added on the #92 review |
| S-3 | Payment holds exactly one account for each reference, so `PaymentStatus.accountId` needs no migration and no new dependency (`SpikeAuthS3PaymentAccountIT`) | Built in #90. The pin is now `paymentStatusCarriesTheAccountSinceSlice4Pr4` |
| S-4 | Kill Bill limits a caller to its tenant, and nothing below it. Another tenant's object answers 500; an object that does not exist answers 404 | It confirms brief rule 5: two different answers tell an outsider which IDs exist. AT-27 checks that Tally gives one answer for both |

## 3. Deviations from the brief or the decision records, and why

### 3.1 Deviations

| # | What | Why | Where |
|---|---|---|---|
| 1 | Keys per caller: the existing `scope` column of each key table holds the caller (`user:<id>`), instead of a new column for the caller | The column already holds "whose key this is"; a second column would hold the same fact twice. The #91 review accepted it | #91 |
| 2 | Access accepts tokens for billing's audience as well as its own, then refuses every token but the service token with 403 | The brief asks for 403 for a household's token, which is made for billing. To answer 403, Access must first accept the token as valid. A token for any other audience gets 401 (#92 review: accepted) | #92 |
| 3 | Access answers **503 with `Retry-After`** when it cannot check a token (no key set kept, provider down), not 500 | The token is not at fault, so the platform keeps it and asks again (#92 review: accepted). Billing still answers 500 in the same case (§4.1) | #92 |
| 4 | After any fetch of the key set, Access uses the set it keeps for a **hold-off** of 30 s before it fetches again (`tally.access.security.key-refetch-hold-off`) | Without it, a stream of tokens with unknown key IDs holds every token behind a provider that hangs (#92 review, finding 1). Cost: a key the provider adds is accepted at most one hold-off after it is first seen | #92 |
| 5 | F16 requires a `Caller` on **every** `/v1/` handler, not only those that name an account, a subscription or a payment | No list of exceptions can go stale (#91 review: stricter is better) | #91 |
| 6 | `AccountApi.open`, which linked no user, is removed from the public API; tests open accounts through `TestAccounts` | #89 review, point 2: after PR-4, no token could reach such an account | #90 |
| 7 | The 401 and 403 answers have no body, only `WWW-Authenticate`; ADR-019 says errors are RFC 9457 problem details | Spring Security's bearer-token entry point answers that way (RFC 6750), and `openapi.yaml` says "No body". See the audit (§6) | #89, #92 |
| 8 | The management port is **8082** | Brief rule 10 asks for a separate port; 8081 is the fake provider's local port | #89 |
| 9 | PR-7's counter tests were written after the counters, and never seen red | The order was reversed by mistake; the #93 description says so. The rules' promtool tests were red first | #93 |
| 10 | `tally-down.md` named port 8080 for the actuator | Corrected to 8082, in #93 | #93 |

### 3.2 Where the code disagrees with the design, and what was done

- **ADR-019, "RFC 9457 problem details".** A 401 or 403 has no body (§3.1, 7). Every other error keeps its problem details.
- **ADR-039, option B for billing.** It is built as decided, but billing has neither the hold-off nor the 503 that Access has: the #92 review's weakness, and §4.1.

### 3.3 Bugs found during the slice

| Bug | Found by | Fixed |
|---|---|---|
| A stream of tokens with unknown key IDs held every token behind a provider that hangs: one more fetch under the decoder's lock for each unknown key ID. The service token waited 7,639 ms | #92 review, finding 1 | #92: the hold-off. `tokensWithUnknownKeyIdsWhileTheProviderHangsDoNotHoldUpTheServiceToken` |
| A request whose handler throws was counted as `answered`: the status is still 200 when the counting filter sees it, and the container makes it a 500 only afterwards. Billing's 500 for a request with no keys was such a request | #93 review | This pull request: the filter counts `failed` and throws the exception again, in billing and in Access. `ApiMetricsRequestOutcomesTest` and `AccessMetricsRequestOutcomesTest` were red first |
| Access's metrics test asked for an answer before the copy was ready, and got 503 when it ran with the other test classes | `./mvnw verify` on #93's branch | #93: the test waits for the first 200, as AT-30 does |

## 4. Open questions and suggested new decision records

### 4.1 From the reviews of #86 to #93

| From | Note | Status |
|---|---|---|
| #86 review | **The service token's lifetime is part of D13.** The streaming platform gets its service token from the same identity provider. While the provider is down, the platform cannot get a new one, so Access answers through an outage only as long as the platform's last token lives. **The tests use one hour** (AT-30) | In row 039 (#87). **Open question**: the platform's side, refreshing early and a lifetime long enough for the outage D13 accepts (§4.3) |
| #88 review, 1 | AT-30 must use a service token with a real lifetime, and show both halves | Done in #92 (one hour) |
| #88 review, 2 | AT-18's separate instances must check tokens | Done in #89 |
| #89 review, 1 | One key scope for every caller of "open an account" until PR-5 | Done in #91 (AT-29) |
| #89 review, 2 | `AccountApi.open` links no user | Done in #90: removed |
| #89 review, 3 | **Accounts opened before V43 have no link.** Nobody can reach them with a token. No production data exists, so nothing is lost | Known limit. A real rollout with data needs a decision first: link the existing accounts, or close them (§4.3) |
| #90 review | Nothing made a new endpoint check the caller | Done in #91: F16 |
| #91 review | **A "pay now" sent again across the PR-5 deploy asks for the payment again.** Keys stored under the old scope are not found under the new one, and "pay now" has no business guard. Together with the #80 note: **no limit on "pay now"**; each press with a new key is one more attempt on a card that was declined | Stated in design.md §8. Known limit; no production data exists. The limit is still the open question of the Slice 3 report §4.3, 2 |
| #92 review | **Billing answers 500 when it cannot check a token**, and **has the same lock with no limit on fetches** (`ConcurrentMapCache`, option B). A billing instance behind a provider that hangs can be slowed by tokens with unknown key IDs. D13 does not cover billing, so the cost is slower answers, not a broken promise | **Open question**: give billing Access's 503 and hold-off (§4.3) |
| #92 review 2 | **A key the provider adds is accepted at most one hold-off (30 s) after it is first seen.** It sits beside ADR-039's other cost: **a key the provider withdraws during an outage stays accepted until a fetch succeeds** | Known limits of 039, stated in `JdbcKeySetCache` |
| #93 review | A request that ends in an exception was counted as `answered` | Done in this pull request (§3.3) |
| #93 (description) | **The key alerts see an outage only when a request makes Tally fetch.** A running instance that holds the keys it needs does not fetch, so it does not see the outage. `identity-keys-unavailable.md` says so | Known limit. The identity provider's own monitoring is the other half |
| #93 (description) | **Access's `failed` count includes the 503 of an instance not yet caught up with the log**, not only the 503 of a token that cannot be checked | Known limit. Neither rule reads `failed`; the runbooks name both causes |

### 4.2 Proposed records, as built

| Record | As built | Suggested "Checked by" |
|---|---|---|
| 032 (authentication) | As written, with WAR-84's limit and decision 1. A token on every `/v1/` request; signature, issuer, audience and expiry checked by Tally; the expiry on the injected clock; the account link in `account.account_user`; 404 for another household; keys per caller. Staff roles are not built (the brief puts them out of scope) | AT-26, AT-27, AT-28, AT-29, AT-30; F16; `ContractIT.everyOperationsUnauthenticatedAnswerConforms`; `AccessContractIT.aRequestWithoutTheServiceTokenIsRefused` |
| 039 (the keys while the provider is down) | Access: option A, with the hold-off (§3.1, 4). Billing: option B. Both: 2 s timeouts | AT-30 (4 tests); `SpikeAuthS2ProviderDownTest`; `IdentityKeysUnavailable` and `AccessIdentityKeysUnavailable` with promtool tests. Billing has no test of its own key settings (§6) |

### 4.3 Suggested new decision records

1. **Billing answers 503 when it cannot check a token, and fetches the keys at most once per hold-off** (from #92). That is Access's option A behaviour without the database. It would amend 039 for billing.
2. **The streaming platform's service token** (from #86): its lifetime, and when the platform refreshes it. This needs the platform's owners. The lifetime is the number D13 depends on during an identity-provider outage.
3. **Accounts with no link** (from #89): before the first rollout with data, either link each existing account to a user, or close it.
4. **The identity provider product** (brief, out of scope): when it is chosen, check its access tokens' `typ`. A token typed `at+jwt` is refused by the default validators (S-1, finding 7).
5. **Staff tokens and roles**: needed for WAR-50, a person's decision on an unknown payment.
6. **ADR-019 and the 401**: either accept that 401 and 403 carry no body (RFC 6750) and say so in 019, or add a problem body to them.

### 4.4 Known limits

- **The service token** lives one hour in the tests, and nothing in Tally can make it live longer during an outage.
- **ADR-039's costs**: a key withdrawn during an outage stays accepted until a fetch succeeds; a key the provider adds is accepted at most one hold-off after it is first seen.
- **Billing during a provider outage**: an instance that starts then answers 500 to every request until the provider is back; a running one can be slowed by tokens with unknown key IDs.
- **Accounts opened before V43** have no link.
- **"Pay now"**: no limit, and a request sent again across the PR-5 deploy asks again.
- **The key alerts** see an outage only when a request makes Tally fetch.

## 5. Fitness functions added

| Check | What it fails | Since |
|---|---|---|
| AT-26 over `openapi.yaml` | Any operation in the contract that answers a bad or expired token with anything but 401, or changes a row | #89 |
| F16 `F16EveryApiHandlerTakesACallerTest`: `everyApiHandlerTakesACaller`, `theRuleSeesEveryApiHandler` | A `/v1/` handler without a `Caller` parameter, so one that could forget the 404 rule; and a rule that finds fewer than the six handlers | #91 |
| `ContractIT.everyOperationsUnauthenticatedAnswerConforms` | An operation's 401 that does not conform to `openapi.yaml` | #89 |
| `AccessContractIT.aRequestWithoutTheServiceTokenIsRefused` | Access's 401 or 403 not conforming to `access-openapi.yaml` | #92 |
| promtool tests for `ApiTokensRefused`, `AccessTokensRefused`, `IdentityKeysUnavailable`, `AccessIdentityKeysUnavailable` | A rule that does not fire at its time, or fires below either floor | #93 |
| F14, both sides (unchanged; now covers the four rules) | A rule reading a metric that is not exported; a page alert with no firing or quiet test | #93 |
| `ApiMetricsIT`, `AccessMetricsIT`, `ApiMetricsRequestOutcomesTest`, `AccessMetricsRequestOutcomesTest` | The counters the rules read not counting 401s, 403s, failed fetches, or a request whose handler throws | #93, this pull request |
| Spike pins `SpikeAuthS1TokenChecksTest`, `SpikeAuthS2ProviderDownTest`, `SpikeAuthS3PaymentAccountIT` | A change in Spring Security's behaviour that the slice relies on | #86 |
| `TestTokensTest` | A test token that would not pass the real checks | #88 |

## 6. Audit of the decision records this slice touched

The audit compares each record with the code: a check counts only if a test exists. Rows 019 and 027 are in the decisions table, with a "Checked by" column. Rows 032, 033, 034, 036 and 039 are proposed, and that table has no such column: for them, the audit compares the record's text with the tests that exist (§4.2 suggests the column for 032 and 039).

| Record | The record says | Check in the code | Gap |
|---|---|---|---|
| **ADR-019** (REST: client `Idempotency-Key` stored before acting; RFC 9457; version in the path; OpenAPI first). Checked by: AT-13; contract tests | A key is stored before acting | AT-13; `SignUpRecoveryIT`; every state-changing operation requires a key (`ContractIT.everyStateChangingOperationRequiresAUuidKey`); the sign-up and pay-now checks of the caller run before the key is claimed (AT-27: nothing stored) | None |
| | Errors are RFC 9457 problem details | `ContractIT` checks every answer against `openapi.yaml` | **401 and 403 have no body** (§3.1, 7; §4.3, 6). The contract says so, so the contract test passes; the record's text does not |
| | OpenAPI first | AT-26 reads its operations from `openapi.yaml`; `ContractIT.theOperationsTestedAreExactlyTheOperationsSpecified`, `everyEndpointOfTheApplicationIsSpecified` | None |
| **ADR-027** (keys: a UUID or 400; held by `platform`, one table per module; claimed before acting; scoped per account until callers are authenticated, then per caller). Checked by: AT-13; `SignUpRecoveryIT`; `ContractIT` | Per caller | AT-29 (2); `Caller.keyScope()` used by the three endpoints that take a key | **Not in the "Checked by" column**: AT-29 should be added. The rolling deploy of the change (keys of the old scope not found) has no test; it is a note in design.md §8 |
| | A UUID or 400, nothing stored | `ContractIT.aKeyThatIsNotAUuidIsRefusedAndNotStored` | None |
| **ADR-032** (authentication), proposed | A signed token on every request, checked by Tally | AT-26 (billing), AT-30 (Access); the expiry on the injected clock (AT-26's expired case, AT-30's outage case) | None |
| | The subject is a user; `POST /v1/accounts` links the user; one account for each user (WAR-84) | AT-28 (4); the named constraint `account_user_one_per_user`, the only failure read as a duplicate (rule 11) | None |
| | Every later request is checked against the link; 404, never 403 | AT-27 (2); F16 | **The equal-answer check covers the account only** (`anotherHouseholdsAccountAnswersAsAnAccountThatDoesNotExist`). The other three operations' answers were compared by reading the code in the #90 review, not by a test |
| | Keys per caller | AT-29 | None |
| | Who calls Access: the streaming platform, audience and scope checked, a household's token refused | AT-30 `onlyTheStreamingPlatformsServiceTokenGetsTheAnswer`; `AccessContractIT.aRequestWithoutTheServiceTokenIsRefused` | None |
| | Staff use the same kind of token; roles come with the first back-office endpoint | Nothing built | **No check, as nothing is built** (out of scope) |
| **ADR-033** (the access feed) | Facts with no personal data (F15); Access applies a message only if its sequence is higher | No change in `contracts/access-facts/` or in the feed in this slice; decision 1, option A, added no user ID to the facts. The feed's tests pass; since #88 they only add tokens to their API calls | None. **The feed's facts still carry no user**, which is what option A of decision 1 relies on |
| **ADR-034** (the contract between billing and Access) | Billing's commit stage fails on a message that is invalid or lacks a field Access reads (F7); Access's tests read every example | `contracts/access-facts/v1/` did not change from c65d47c to 8b8adde; the F7 tests pass on both sides | None. **Authentication is not part of this contract**: the service token belongs to `access-openapi.yaml`, which `AccessContractIT` checks |
| **ADR-036** (Access's readiness; liveness never broken by the log or the database) | Not ready answers 503; the health probes stay open | The probes are not under `/v1/` and need no token (`AccessStartWithoutLogIT`, with the service token on its `/v1/` calls since #88) | **503 now has two meanings** at Access: not ready, and a token that cannot be checked (with `Retry-After`). `access-openapi.yaml` names both; the `failed` count does not tell them apart (§4.1) |
| **ADR-039** (the keys while the provider is down), proposed | Access, option A: the last key set in Access's database, written on each successful fetch, read when the decoder is built, fetched again only for an unknown key ID | AT-30 `duringAnOutageAccessAnswersWhileTheServiceTokenLivesAndRefusesItAfter` (no fetch during the outage; a new instance answers from the database); `anInstanceThatNeverFetchedTheKeysAnswers503DuringAnOutage` | None |
| | Both: short HTTP timeouts | Access: `tokensWithUnknownKeyIdsWhileTheProviderHangsDoNotHoldUpTheServiceToken` (under 3 s while the provider hangs) | **Billing's 2 s timeouts have no test.** The setting is in `ApiSecurity`; only spike S-2 shows the default would be 30 s |
| | Billing, option B: the keys in memory until an unknown key ID; an instance started during an outage refuses tokens | `SpikeAuthS2ProviderDownTest.aSpringCacheKeepsTheKeysUntilAnUnknownKeyIdAndFetchesOnlyThen` shows Spring's behaviour with a Spring cache | **No test of billing's own configuration**: nothing runs billing with the provider down. And billing answers 500, not 401, when it has no keys (§4.1) |
| | The limit: the service token's lifetime is part of D13; the report states the number | One hour, in AT-30 (`SERVICE_TOKEN_LIFETIME`) | None in Tally. The platform's side is the open question of §4.3, 2 |
| | (Added by the #92 review) The hold-off | AT-30's flood test; the javadoc of `JdbcKeySetCache` states the cost | The record's text does not name the hold-off. Suggested: add it to 039 with its cost, when the user next amends the record |
