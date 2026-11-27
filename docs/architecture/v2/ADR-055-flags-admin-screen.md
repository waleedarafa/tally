# ADR-055. A minimal flags admin screen: static files over the admin API

**Status:** Accepted (by delegation, Waleed 2026-11-18 "Feel free to take decisions"), 27 November 2026 (WAR-66 follow-up).

## Context

ADR-053 gave flags an admin API (`/v1/admin/flags`, scope `billing:admin`, `Idempotency-Key` on every change) and a report, and left the screen open. Tally has no user interface of any kind today: no `static/` or `templates/` resources, no template engine, no JavaScript build, and the only dependency for the web is `spring-boot-starter-webmvc`. `ApiSecurity` matches `/v1/**` only, is stateless and has no login page: a caller brings a token from the identity provider (ADR-032). Staff roles exist only as the token scope `billing:admin`.

## Options

- **A. Static files served by `tally`** (one HTML page and one script under `classpath:/static/admin/flags/`), calling the existing API from the browser with a staff token.
- **B. Server-rendered pages** (Thymeleaf or similar). Needs a template dependency, a browser login (session, CSRF, a redirect to the identity provider) and a second way to call the same rules: a second security model for one screen.
- **C. A single-page app with its own build** (npm, a framework). A new toolchain for the pipeline and the repo, for ten buttons.
- **D. A separate back-office deployable.** ADR-053 already rejected a separate admin deployable until a second back-office function exists.

## Decision

We take option A.

1. **Where.** `tally/src/main/resources/static/admin/flags/index.html` and `flags.js`, served by Spring Boot's static resource handling at `/admin/flags/`. No new dependency, no build step, no framework; plain HTML and modern browser JavaScript (`fetch`, `crypto.randomUUID`).
2. **Security.** The page is outside `/v1/**`, so `ApiSecurity` does not cover it, and it needs no protection: it holds no data and no secret. Every read and change goes through `/v1/admin/flags` with `Authorization: Bearer <token>`, so scope checks, 403 for a household, validation, the advisory lock, idempotency and the audit log line all stay in the API (ADR-053). The operator pastes a staff token into a field; the script keeps it in memory only (never `localStorage`, `sessionStorage` or a cookie), so closing the tab forgets it. A browser login with the identity provider is a later step, when a second back-office function exists.
3. **What it shows and does.** The report (`asOf`, every flag with default, account and country assignments, released at, remove by, overdue, number of changes, last change) with overdue flags marked and listed at the top; a flag's detail with its change log; and the operations: define, set default, assign or clear an account, assign or clear a country, release, remove. Each change sends a fresh `Idempotency-Key` (`crypto.randomUUID()`), then reloads the report. A refusal shows the status code and the response body text.
4. **Safety in the page.** Data from the API is written with `textContent`, never `innerHTML`, so a value cannot inject markup. Remove asks for confirmation.
5. **Tests.** An integration test serves the page and the script without a token (200, HTML and JavaScript), checks that the page loads the script, that the script calls only `/v1/admin/flags` paths with `Idempotency-Key` and a bearer header, and that it never uses `localStorage`, `sessionStorage`, `document.cookie` or `innerHTML`. The API itself stays covered by `FlagsAdminApiIT` and `ContractIT`. Browser-level tests are not added: there is no browser test toolchain, and adding one is option C's cost.

## Consequences

- Staff can manage flags and see overdue ones without curl.
- The page is public as a file; it is useless without a token with `billing:admin`.
- The first back-office function with real users will need an identity provider login; this screen then moves behind it, and its script does not change much (only where the token comes from).

## Rejected

- **B:** a second security model (sessions, CSRF) for one screen.
- **C:** a JavaScript toolchain in a Java repo for one screen.
- **D:** see ADR-053.

## Drivers

ADR-010; ADR-032; ADR-053; F-07; I-02.

## Revisit when

A second back-office screen is needed, or staff get identity provider logins.
