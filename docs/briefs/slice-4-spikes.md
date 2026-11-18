# Slice 4 spikes: findings

Four spikes from `slice-4.md`, before PR-2. Three are tests in `tally/src/test/java/com/tally/spikes/`; each pins what it found, so a change to it fails the build. S-1 and S-2 are unit tests (`*Test`, commit stage), with no Spring context and no database. S-3 is an integration test (acceptance stage). S-4 is a reading of Kill Bill's code, with pinned citations.

**Where the facts come from.** `docs.spring.io` is not reachable from this session, so the official documentation was read from its source in the Spring projects' repositories, at the tags Tally uses, and from the artifacts on Maven Central:

- Spring Boot 4.1.1 reference, `security/oauth2.adoc` ("Resource Server") and `web/spring-security.adoc`, tag `v4.1.1`.
- Spring Security 7.1.1 reference, `servlet/oauth2/resource-server/jwt.adoc`, tag `7.1.1`; its source code at the same tag.
- `spring-boot-dependencies-4.1.1.pom` (it imports `spring-security-bom` 7.1.1), the starter's POM and the `-sources.jar` of `spring-boot-security-oauth2-resource-server` 4.1.1.

The one dependency the spikes add is `org.springframework.security:spring-security-oauth2-jose`, test scope, version from Spring Boot's dependency management. It holds the decoder, the validators and Nimbus (`nimbus-jose-jwt` 10.9.1). Spring Boot's security auto-configuration is **not** on the classpath, so no endpoint changes before PR-3.

| Spike | Question | Answer | Pinned by |
|---|---|---|---|
| S-1 | Which starter, which version, which settings? Can the expiry check use the injected `Clock`? | **`spring-boot-starter-security-oauth2-resource-server`**, version from Boot 4.1.1 (Spring Security 7.1.1). Settings `spring.security.oauth2.resourceserver.jwt.issuer-uri`, `.jwk-set-uri`, `.audiences` (and `.public-key-location`). **The clock: yes**, with one `OAuth2TokenValidator<Jwt>` bean, a `JwtTimestampValidator` given the injected `Clock`; without it the check reads the system clock | `SpikeAuthS1TokenChecksTest` (5 tests) |
| S-2 | The identity provider is down: (a) a running instance, (b) an instance that starts | **(a) Boot's default keeps the keys 5 minutes, then refuses every token. (b) An instance starts, and refuses every token until the provider is back.** Both can be changed with documented options; (b) needs a copy of the keys that outlives the process, which Spring does not provide. **Access cannot meet D13 with the defaults: this is the brief's stop condition** (see "Questions for the user") | `SpikeAuthS2ProviderDownTest` (9 tests) |
| S-3 | Can each controller find its account with no new module dependency? `getPaymentStatus` | **Yes.** Payment already holds exactly one account per reference; `PaymentStatus` can gain it as an added field, with no migration. `account` and `payment` both use `platform`, which uses neither, so a caller type in `platform` needs no new line in the graph | `SpikeAuthS3PaymentAccountIT` (3 tests) |
| S-4 | How does Kill Bill authenticate a caller and limit it to its own tenant? | **Two layers: a tenant from an API key and secret in headers, and a user (Shiro: basic or bearer) who is not bound to any tenant.** Every query is filtered by the tenant. **An object of another tenant answers 500, not 404**; only an object that does not exist answers 404. Kill Bill has no limit below the tenant: no counterpart to "a household acts only for its own account" | Kill Bill `killbill` at 3868a5b, read in the code (§S-4) |

## S-1. The settings, and the clock

### Findings

| # | Finding | Evidence |
|---|---|---|
| 1 | **The starter is `spring-boot-starter-security-oauth2-resource-server`** (4.1.1). It brings `spring-boot-starter-security` and `spring-boot-security-oauth2-resource-server`, which brings `spring-security-oauth2-resource-server` and `spring-security-oauth2-jose`. The name of Boot 3, `spring-boot-starter-oauth2-resource-server`, does not exist for 4.1.1 (Maven Central answers 404) | Maven Central: `spring-boot-starter-security-oauth2-resource-server-4.1.1.pom`; `spring-boot-security-oauth2-resource-server-4.1.1.pom` |
| 2 | **The settings:** `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` or `.issuer-uri`; `.audiences` ("the expected values of the `aud` claim"); `.public-key-location` when the provider has no key set URI | Boot reference, `security/oauth2.adoc`, "Resource Server" |
| 3 | **How Boot builds the decoder.** One `JwtDecoder` bean, unless the application declares its own (`@ConditionalOnMissingBean(JwtDecoder.class)`): `NimbusJwtDecoder.withJwkSetUri` for `jwk-set-uri`; a `SupplierJwtDecoder` around `withIssuerLocation` for `issuer-uri` alone; `withPublicKey` for `public-key-location`. Its validator is `JwtValidators.createDefaultWithValidators` over: the issuer check (when `issuer-uri` is set), the audience check (when `audiences` is set), and **every `OAuth2TokenValidator<Jwt>` bean** | `JwtDecoderConfiguration.java` (Boot 4.1.1 sources): `@ConditionalOnMissingBean` :65; the validator beans :70-79; `withJwkSetUri` :116-135; `SupplierJwtDecoder` :137-140; `getValidator` :158-168 |
| 4 | **The clock.** `createDefaultWithValidators` adds its own `JwtTimestampValidator` (system clock) only when none of the given validators is one. So **one bean, a `JwtTimestampValidator` with `setClock(clock)`, puts the injected clock into the expiry check**, and Boot's other checks stay. The default leeway is 60 seconds: a token is accepted until 60 s after its `exp` | `JwtValidators.java` :93-112; `JwtTimestampValidator.java` :54, :104, :130. Test: `theExpiryCheckUsesTheInjectedClockWithASixtySecondLeeway` (accepted at `exp` + 59 s, refused at `exp` + 61 s, on the test clock) |
| 5 | **Without that bean, the system clock decides.** Moving the injected clock past a token's end changes nothing | `withoutTheBeanTheExpiryCheckReadsTheSystemClock` |
| 6 | A wrong issuer, a wrong audience and a bad signature are each refused; the subject is the token's `sub` | `aValidTokenGivesItsSubjectAsTheUser`, `aWrongIssuerAWrongAudienceAndABadSignatureAreEachRefused` |
| 7 | **The `typ` header must be `JWT` or absent.** A token typed `at+jwt` (RFC 9068 access tokens, which some identity providers send) is refused by the default validators | `theTypeHeaderMustBeJwtOrAbsent`; `JwtTypeValidator.java` :55-57 |

### What this means for the slice

- PR-3 adds `spring-boot-starter-security-oauth2-resource-server` to `tally/pom.xml` (and PR-6 to `access/pom.xml`), and removes the test-scope `spring-security-oauth2-jose` the spikes added: the starter brings it.
- Rule 9 of the brief (time from the injected `Clock`) costs one bean, in each deployable. A fitness function can check that the decoder's validators include a `JwtTimestampValidator` on the injected clock: AT-26's "expired token (the clock moved)" fails without it (finding 5), so AT-26 is that check.
- When the identity provider product is chosen (out of scope), check its access tokens' `typ` (finding 7).

## S-2. The identity provider is down

"Down" is a 503 or a stopped port; "hangs" accepts the connection and never answers. The stand-in provider is a JDK `HttpServer` in the test (`SpikeAuthTokens.Provider`).

### Findings

| # | Case | What happens | Evidence |
|---|---|---|---|
| 1 | (a) Running, `jwk-set-uri`, Boot's default | The keys are fetched on the first token and kept by Nimbus's cache for **5 minutes** (`JWKSourceBuilder.DEFAULT_CACHE_TIME_TO_LIVE`). A provider stopped within that time changes nothing | `aRunningInstanceKeepsTheKeysForFiveMinutesAndAcceptsTokensWhileTheProviderIsStopped`; Spring Security reference, `jwt.adoc`: "caches in-memory the authorization server's JWK set for 5 minutes"; `NimbusJwtDecoder.java` :468-474 |
| 2 | (a) After the 5 minutes, provider down | **Every token is refused**: the default cache is not outage tolerant. Shown on Nimbus's same cache with a 1-second time to live, because the 5-minute one reads the system time | `afterTheTimeToLiveEveryTokenIsRefusedWhileTheProviderIsDown` |
| 3 | (a) Option: Nimbus's outage tolerance, through `NimbusJwtDecoder.withJwkSource` | The last keys keep working after the time to live while the provider is down | `anOutageTolerantSourceKeepsAcceptingAfterTheTimeToLive`; `NimbusJwtDecoder.java` :276 |
| 4 | (a) Option: a Spring `Cache` (`withJwkSetUri(...).cache(cache)`) | Nimbus's own cache is switched off; the Spring cache keeps the key set **until a token names a key ID it does not hold**. No fetch at all while the provider is down; a key that rotates in during the outage is refused until the provider is back, then accepted | `aSpringCacheKeepsTheKeysUntilAnUnknownKeyIdAndFetchesOnlyThen`; `NimbusJwtDecoder.java` :473, :544-548 |
| 5 | (a) The provider hangs | Each request waits for the decoder's HTTP read timeout, **30 s by default**, and the fetch holds a lock, so every request behind it waits too. Shown at 500 ms (the test sets `sun.net.client.defaultReadTimeout`); log line `SPIKE-AUTH-S2 a hanging provider held the request for … ms` | `aHangingProviderHoldsTheRequestForTheReadTimeout`; `JwtDecoderProviderConfigurationUtils.java` :89, :99; `NimbusJwtDecoder.java` :544 (the lock), :406 (`restOperations`, to set shorter timeouts) |
| 6 | (b) Starts while down, `jwk-set-uri` | **Starts** (the decoder is built without calling the provider). **Every token is refused** until the provider is back; then accepted with no restart | `jwkSetUriStartsWhileTheProviderIsDownRefusesTokensAndRecovers` |
| 7 | (b) Starts while down, `issuer-uri` alone | **Starts** (discovery waits for the first token, `SupplierJwtDecoder`). Every token is refused while down; the first one after the provider is back is accepted: a failed discovery is not remembered | `issuerUriStartsWhileTheProviderIsDownRefusesTokensAndRecovers`; Spring Security reference, `jwt.adoc`, "Startup Expectations" |
| 8 | (b) Option: a key set kept from an earlier run | A Spring `Cache` that already holds the key set's JSON is read when the decoder is built, so **a new instance accepts tokens while the provider is down, with no fetch**. Spring Security keeps it in memory only; **a store that outlives the process would be Tally's own** | `aKeySetKeptFromAnEarlierRunLetsANewInstanceAcceptTokensWhileTheProviderIsDown`; `NimbusJwtDecoder.java` :518 |
| 9 | (b) Option: `public-key-location` | No provider at all, so an outage changes nothing; **no key rotation**: a token signed with a new key is refused until the setting changes and the instance restarts | `aConfiguredPublicKeyNeedsNoProviderAndDoesNotRotate` |

### What this means for the slice

- **D13 for Access, with Boot's defaults:** a running Access answers for at most 5 minutes after the provider goes down, and an Access that starts during an outage answers nothing (every request 401). D13 asks "Can I watch?" to be answered 99.9% of the month. **This is the brief's stop condition** ("S-2 shows that Access cannot answer while the identity provider is down"). The options and a recommendation are below; the choice is the user's.
- **A hanging provider** (finding 5) is worse than one that is down: with the default timeouts, every request of the instance can wait 30 s. Whatever the choice above, the decoder should get short timeouts (`restOperations`), and PR-7's alert on "keys that cannot be fetched" covers it.
- Billing: D13 does not apply. A running billing instance keeps the keys 5 minutes (default) or until an unknown key ID (finding 4).

## S-3. The account of a payment reference

### Findings

| # | Finding | Evidence |
|---|---|---|
| 1 | `PaymentStatus` is `(reference, state, failureReason)`: no account | `paymentStatusCarriesNoAccountToday` |
| 2 | **Payment holds exactly one account per reference.** `payment.collection_request` has one row per reference (constraint `collection_request_one_per_reference`), with `account_id NOT NULL`; every attempt carries `account_id NOT NULL`, and a retry copies it. A second `requestCollection` for the same reference with another account keeps the first request and logs a warning | `paymentKnowsTheOneAccountOfEachReference`; `V6__payment.sql`, `V14__collection_request.sql`; `PaymentService.requestCollection` (the `!created` branch); `PaymentAttempt.retry` copies `accountId` |
| 3 | **No new dependency.** The pinned graph has `platform: shared`, `account: shared, platform`, `payment: shared, platform`. A type in `platform` that holds the caller (and, for a user, the account) can be filled by `account` and read by `payment`'s web layer; `platform` imports neither | `thePinnedGraphLetsPaymentAndAccountShareATypeInPlatform`; `module-dependencies.txt` |

### What this means for the slice

- PR-4: `PaymentStatus` gains `accountId` (a reference type, may be null by rule 7's habit, though every row has one), read from the latest attempt; the controller compares it with the caller's account and answers 404 when they differ or when no attempt exists. No migration.
- The other controllers name the account in the path (`getAccount`, `payNow`) or in the body (`signUp`): a direct comparison with the caller's account.

## S-4. How Kill Bill authenticates a caller

Read in Kill Bill's code at commit `3868a5b2339c3b86531904d6738a36e1e82933bf` (the commit killbill-wiki cites), fetched for this spike. Links are to that commit. Nothing was run; one point is marked **inference**.

### Findings

1. **A tenant is a pair of headers, checked on every request.** `TenantFilter` reads `X-Killbill-ApiKey` and `X-Killbill-ApiSecret` ([TenantFilter.java:76-82](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/profiles/killbill/src/main/java/org/killbill/billing/server/security/TenantFilter.java#L76), [JaxrsResource.java:41-42](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/jaxrs/src/main/java/org/killbill/billing/jaxrs/resources/JaxrsResource.java#L41)). Missing or wrong, the answer is **401** ([:85-99](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/profiles/killbill/src/main/java/org/killbill/billing/server/security/TenantFilter.java#L85), [:149-153](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/profiles/killbill/src/main/java/org/killbill/billing/server/security/TenantFilter.java#L149)), except for the paths that need no tenant (tenant creation, permissions, metrics: [:144-147](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/profiles/killbill/src/main/java/org/killbill/billing/server/security/TenantFilter.java#L144), [KillBillApiDefinition.java:227-232](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/jaxrs/src/main/java/org/killbill/billing/jaxrs/resources/KillBillApiDefinition.java#L227)).
2. **The secret is checked by Kill Bill against its own table**, through Shiro: `select api_secret, api_salt from tenants where api_key = ?`, with the result cached ([KillbillJdbcTenantRealm.java:44](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/profiles/killbill/src/main/java/org/killbill/billing/server/security/KillbillJdbcTenantRealm.java#L44), [:57](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/profiles/killbill/src/main/java/org/killbill/billing/server/security/KillbillJdbcTenantRealm.java#L57)). No token, no identity provider for the tenant.
3. **The tenant is put on the request once**, and every resource reads it from there ([TenantFilter.java:101-104](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/profiles/killbill/src/main/java/org/killbill/billing/server/security/TenantFilter.java#L101), [Context.java:112-120](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/jaxrs/src/main/java/org/killbill/billing/jaxrs/util/Context.java#L112)). The same shape as the brief's rule 6: found once per request, read by every module's web layer.
4. **A user is a second, separate layer**, and only when RBAC is on: Shiro's basic or bearer filters, with realms for Kill Bill's own users table, LDAP, Okta and Auth0 ([KillBillShiroWebModule.java:113-131](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/profiles/killbill/src/main/java/org/killbill/billing/server/modules/KillBillShiroWebModule.java#L113)). **A user is not bound to a tenant:** the user, role and permission queries name no tenant ([KillBillJdbcRealm.java:32-34](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/util/src/main/java/org/killbill/billing/util/security/shiro/realm/KillBillJdbcRealm.java#L32)), and the `users` table has no tenant column ([ddl.sql:264-275](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/util/src/main/resources/org/killbill/billing/util/ddl.sql#L264)). The tenant comes only from the headers.
5. **Every query is filtered by the tenant**: `tenant_record_id = :tenantRecordId` in the shared SQL templates ([EntitySqlDao.sql.stg:134-135](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/util/src/main/resources/org/killbill/billing/util/entity/dao/EntitySqlDao.sql.stg#L134)), so one tenant cannot list another's rows.
6. **A request that names an object is checked against the tenant first.** `GET /accounts/{id}` builds its context from the id ([AccountResource.java:206-212](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/jaxrs/src/main/java/org/killbill/billing/jaxrs/resources/AccountResource.java#L206), [DefaultAccountUserApi.java:79-81](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/account/src/main/java/org/killbill/billing/account/api/user/DefaultAccountUserApi.java#L79)), and `getAccountRecordIdSafe` compares the object's tenant with the caller's ([InternalCallContextFactory.java:381-386](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/util/src/main/java/org/killbill/billing/util/callcontext/InternalCallContextFactory.java#L381), [:421-432](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/util/src/main/java/org/killbill/billing/util/callcontext/InternalCallContextFactory.java#L421)). A second copy of that check, in `createInternalTenantContext(objectId, …)`, is commented out ([:139-143](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/util/src/main/java/org/killbill/billing/util/callcontext/InternalCallContextFactory.java#L139)); the check in `getAccountRecordIdSafe` runs on the same path.
7. **Another tenant's object answers 500, not 404.** An object that does not exist throws `ObjectDoesNotExist` ([:442-448](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/util/src/main/java/org/killbill/billing/util/callcontext/InternalCallContextFactory.java#L442)), which the mapper turns into 404 "likely object for wrong tenant"; an object that exists in another tenant throws a plain `IllegalStateException` ([:385](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/util/src/main/java/org/killbill/billing/util/callcontext/InternalCallContextFactory.java#L385)), which the same mapper turns into an internal error ([IllegalStateExceptionMapper.java:40-46](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/jaxrs/src/main/java/org/killbill/billing/jaxrs/mappers/IllegalStateExceptionMapper.java#L40)). Read in the code, not run.
8. **No limit below the tenant (inference).** No check that a caller owns the account it names was found on the path of finding 6; the tenant is a merchant, and its accounts are its customers, who are not callers. Inference: the absence was looked for on that one path, not across the code.

### Compared with Tally's brief

| | Kill Bill | Tally (brief) |
|---|---|---|
| Who calls | A merchant (tenant) with an API key and secret; optionally a staff user | A household user with a signed token; a service with a service token |
| Who checks the secret | Kill Bill, against its own table | The identity provider signs; Tally checks the signature with public keys (rule 1) |
| The unit a caller is limited to | The tenant | The household's account (rule 3), below anything Kill Bill limits |
| Found once per request | The tenant, on the request (finding 3) | The caller's account, through a type in `platform` (rule 6) |
| Another's object | 500 if it exists, 404 if not (finding 7) | 404 always (rule 5): a 500 says "this exists" as a 403 would |

Rule 5 of the brief is confirmed by finding 7: Kill Bill's two answers tell an outsider which IDs exist.

## Questions for the user

1. **S-2: Access and an identity provider that is down (D13).** With Boot's defaults, a running Access refuses every token 5 minutes into an outage, and an Access that starts during one refuses every token (findings S-2 1, 2, 6, 7). The brief says to stop and ask. Options:

   | Option | What it is | D13 | Cost |
   |---|---|---|---|
   | **A. Keep the last key set in Access's own database (recommended)** | The decoder's Spring `Cache` is backed by one row in Access's PostgreSQL: written on each successful fetch, read when the decoder is built. Refetched only for an unknown key ID (S-2 4, 8) | A running instance and a new one both answer through an outage, with the keys of before it | One table and a small `Cache` class in Access. A key the provider withdraws during an outage stays accepted until a fetch succeeds |
   | B. Keep the keys in memory only | A Spring `Cache` in memory, or Nimbus's outage tolerance (S-2 3, 4) | A running instance answers through an outage; a new one does not until the provider is back | No storage. A restart or a new instance during an outage answers 401 |
   | C. A public key in settings | `public-key-location` (S-2 9) | Never depends on the provider | No rotation: each new key is a settings change and a restart, coordinated with the provider |

   Recommended: **A**, for Access. For billing, B (D13 does not apply to it). In every option, the decoder gets short HTTP timeouts (S-2 5). PR-1 records the question as proposed decision 039, with these options and the recommendation; PR-6 (Access) waits for the user's choice.
