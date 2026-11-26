# ADR-053. An admin API and a report for feature flags

**Status:** Accepted, 26 November 2026 (WAR-66 follow-up; taken by delegation).

## Context

ADR-010 asks for flags per account or country, changes logged, removal within 30 days of full release, and "a flag report" as its check. ADR-048 and ADR-049 built the catalogue (`Flags`, `FlagCatalogue`, V56) and evaluation (`FlagsApi`). Nothing lets a person change a flag or see the report: today a flag can only be changed in code or SQL. WAR-66 left the admin API and the report open (F-07 "Partly built").

Three things have no precedent in the code:
1. **Who may call it.** Every `/v1/` request needs a valid token (ADR-032), and a household's token is valid on all of `/v1/`. ADR-032 left staff roles "to the first back-office endpoint". This is that endpoint.
2. **A catalogue written by replacing it.** ADR-049 saves the whole snapshot (delete, then insert). Two admin changes at the same moment would each load the old catalogue, and the later save would silently drop the earlier change.
3. **No actor in the change log.** `FlagChange` is a time and a description. `Flags.remove` discards the flag and its log.

## Decision

**Authorization.** Everything under `/v1/admin/` needs the scope `billing:admin` in the token (Spring authority `SCOPE_billing:admin`), added to `ApiSecurity`'s `/v1/**` chain. A token without it, including every household's, gets 403; no token or an invalid one stays 401 as today. The token's subject is the staff user: it is the caller's idempotency scope (`Caller.keyScope()`, ADR-027) and is written to the application log with every change. One scope is the first step; finer roles come when a second back-office function needs them (ADR-032 revisit). The identity provider's side (who is given the scope) is outside Tally, as for every other token.

**Endpoints** (module `flags`, `adapter.web`; every request that changes state needs an `Idempotency-Key`, rule 13, with the module's own key table `flags.idempotency_key`, V60):

| Request | Meaning | Answers |
|---|---|---|
| `GET /v1/admin/flags` | the report | 200 |
| `GET /v1/admin/flags/{name}` | one flag with its full change log | 200, 404 |
| `POST /v1/admin/flags` `{name}` | define: off by default | 201, 400, 409 |
| `PUT /v1/admin/flags/{name}/default` `{on}` | set the default | 200, 404, 409 |
| `PUT` / `DELETE /v1/admin/flags/{name}/accounts/{accountId}` `{on}` | assign or clear for one account | 200, 400, 404, 409 |
| `PUT` / `DELETE /v1/admin/flags/{name}/countries/{country}` `{on}` | assign or clear for one country (ISO 3166-1 alpha-2) | 200, 400, 404, 409 |
| `POST /v1/admin/flags/{name}/release` | full release | 200, 404, 409 |
| `DELETE /v1/admin/flags/{name}` | remove | 204, 404 |

A change answers with the flag as the report shows it. 400 is a bad name (`[a-z][a-z0-9-]*`), country or body; 404 an unknown flag; 409 a change the rules refuse (already defined, already released, release with the default off or an off assignment). The rules are those of `Flags` (ADR-048): nothing here adds or relaxes one.

**The report.** `GET /v1/admin/flags` answers `asOf` (the injected clock) and, for each flag sorted by name: the name, the default, the account and country assignments, `releasedAt` (null before release), `removeBy` (release + 30 days, null before release), `overdue` (`Flags.isOverdue`: released more than 30 days before `asOf` and not removed), the number of changes and the time of the last. The top level also lists `overdue` flag names, so the check of ADR-010 is one read. A removed flag is not listed.

**Changes are serialized.** `FlagCatalogue` gains `loadForUpdate()`: it takes a transaction-scoped advisory lock for the flags catalogue, then loads. Every admin change runs `loadForUpdate`, changes, `save` in one transaction, so a second change waits for the first and then sees its result. `load()` (evaluation, the report) takes no lock. ADR-049 is not edited: this adds one method to the port and keeps load and save as they are.

**Audit.** Each change writes one application log line with the staff user, the flag and the change. The persisted change log is unchanged: it still has no actor, and removing a flag still discards its log (ADR-049, `Flags.remove`); the log line is then the only trace of a removal. Adding an actor to `FlagChange`, and a durable removal record, is a follow-up, not decided here.

**Module rules.** The web adapter and the application service sit inside `flags` (`adapter.web`, `application`); `flags` still depends on `shared` and `platform` only (`Caller` and `IdempotentEndpoints` are `platform`'s). The admin operations are a public interface of the module (`FlagsAdminApi`) with its value types, for the adapter and for a later back-office; the catalogue stays behind the port.

## Consequences

- A person with the scope can define, assign, default, release and remove flags and read the report, over HTTP, with the same rules and the same log as the domain has.
- A household's token on `/v1/admin/` is refused with 403, and nothing changes.
- `docs/api/openapi.yaml` gains the admin paths and a bearer scope note; contract tests validate them.
- Concurrent changes do not lose updates; a long-held lock is bounded by the transaction.
- The audit gap above stays visible in the topic tracker.

## Rejected

- **A separate admin deployable or port:** no second back-office function exists to justify it; the management port is for metrics.
- **Reading the staff role from a custom claim:** the scope is what Spring maps without code and what Access already uses (`access:read`).
- **A report only in SQL or in a log:** ADR-010's check should not need database access.
- **Optimistic version on the whole catalogue:** the snapshot has no version column; adding one is a migration for no gain over a lock on so small a table.
- **Percentage or sticky assignments:** hosted percentage flags stay rejected (ADR-010, O3).

## Drivers

ADR-010; ADR-032; ADR-048; ADR-049; ADR-019 (idempotency); O3; D7; F-07.

## Revisit when

A second back-office function needs roles, or finance asks for who changed a flag.
