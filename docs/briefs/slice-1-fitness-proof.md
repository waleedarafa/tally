# Slice 1: fitness function proof

Every fitness function from build step 2 was shown to fail on a deliberate violation. Each violation was then removed and the build went green again.

## Method

For each case below, a script:

1. copied the violation classes into `tally/src/main/java` (production sources, where the rules look), and for v8b also replaced `pinned-events.txt`;
2. ran `./mvnw -B verify` from the repository root;
3. saved the Maven log and the failure messages from the Surefire reports;
4. restored the tree with `git checkout -- tally/src` and `git clean -fd -- tally/src`.

All runs were against commit `3310716` ("Slice 1 step 2: fitness functions"), with OpenJDK 25.0.4, Maven 3.9.11 (wrapper), Spring Boot 4.1.1, Spring Modulith 2.1.1 and ArchUnit 1.4.2.

Every violation run exited with code 1 and `BUILD FAILURE`. In each one, the build failed in the `tally` module's Surefire (unit test) phase, before the integration tests. None failed at compilation.

## Summary

| Case | Fitness function | Guards | Failing test | Result |
|---|---|---|---|---|
| v1a | FF-1: Modulith: only declared module dependencies | ADR-003, ADR-014 | `ModuleStructureTest.modulesRespectTheirDeclaredBoundaries` | fails as expected (exit 1) |
| v1b | FF-1: Modulith: no access to another module's internal packages | ADR-003 | `ModuleStructureTest.modulesRespectTheirDeclaredBoundaries` | fails as expected (exit 1) |
| v1c | FF-1: Modulith: `platform` depends on no business module | ADR-027 | `ModuleStructureTest.modulesRespectTheirDeclaredBoundaries` | fails as expected (exit 1) |
| v1d | FF-1: Modulith: no cycles between modules | ADR-001, ADR-003 | `ModuleStructureTest.modulesRespectTheirDeclaredBoundaries` | fails as expected (exit 1) |
| v1e | FF-2: Top-level packages are exactly the agreed modules | CLAUDE.md rule 1 | `ModuleStructureTest.topLevelPackagesAreExactlyTheAgreedModules` | fails as expected (exit 1) |
| v2 | FF-3: No JPA relations between modules | ADR-001, ADR-003 | `ArchitectureRulesTest.noJpaRelationsBetweenModules` | fails as expected (exit 1) |
| v3 | FF-4: No direct `now()` or system clock | D4, ADR-003 | `ArchitectureRulesTest.noDirectReadsOfTheSystemClock` | fails as expected (exit 1) |
| v4 | FF-5: No `@Scheduled` outside `platform` | ADR-017, ADR-027 | `ArchitectureRulesTest.noSchedulingOutsidePlatform` | fails as expected (exit 1) |
| v4b | FF-5: No `@EnableScheduling` outside `platform` | ADR-017, ADR-027 | `ArchitectureRulesTest.noSchedulingOutsidePlatform` | fails as expected (exit 1) |
| v5 | FF-6: No thread or executor creation outside `platform` | ADR-027 | `ArchitectureRulesTest.noThreadOrExecutorCreationOutsidePlatform` | fails as expected (exit 1) |
| v6 | FF-7: Nothing in `payment` imports `invoicing` | ADR-014 | `ArchitectureRulesTest.paymentDoesNotDependOnInvoicing` | fails as expected (exit 1) |
| v7 | FF-8: `domain` does not depend on adapters or web | ADR-003 | `ArchitectureRulesTest.domainDoesNotDependOnAdapters` | fails as expected (exit 1) |
| v8a | FF-9: Pinned event classes: a new event must be pinned | ADR-009 | `PinnedEventClassesTest.eventClassesMatchThePinnedList` | fails as expected (exit 1) |
| v8b | FF-9: Pinned event classes: an event cannot be moved or renamed | ADR-009 | `PinnedEventClassesTest.eventClassesMatchThePinnedList` | fails as expected (exit 1) |
| v9 | FF-10: Every background job has a health signal | ADR-023 | `ArchitectureRulesTest.everyBackgroundJobReportsHealth` | fails as expected (exit 1) |

Fitness function to test mapping:

| ID | Rule (brief step 2) | Test |
|---|---|---|
| FF-1 | Spring Modulith verification: no cycles, no internal access, only declared dependencies (so `platform` depends on no business module) | `ModuleStructureTest.modulesRespectTheirDeclaredBoundaries` |
| FF-2 | Exactly the agreed top-level modules (CLAUDE.md rule 1). An addition to the brief's list. | `ModuleStructureTest.topLevelPackagesAreExactlyTheAgreedModules` |
| FF-3 | No cross-module JPA relations | `ArchitectureRulesTest.noJpaRelationsBetweenModules` |
| FF-4 | No direct `now()` | `ArchitectureRulesTest.noDirectReadsOfTheSystemClock` |
| FF-5 | No `@Scheduled` outside `platform` | `ArchitectureRulesTest.noSchedulingOutsidePlatform` |
| FF-6 | No thread or executor creation outside `platform` (from ADR-027's compliance section; not in the brief's list) | `ArchitectureRulesTest.noThreadOrExecutorCreationOutsidePlatform` |
| FF-7 | Nothing in `payment` imports `invoicing` | `ArchitectureRulesTest.paymentDoesNotDependOnInvoicing` |
| FF-8 | `domain` does not depend on adapters | `ArchitectureRulesTest.domainDoesNotDependOnAdapters` |
| FF-9 | Pinned event class list | `PinnedEventClassesTest.eventClassesMatchThePinnedList` |
| FF-10 | Every registered job has a health signal | `ArchitectureRulesTest.everyBackgroundJobReportsHealth` |

## Evidence

### v1a: Modulith: only declared module dependencies (FF-1)

**Violation.** `payment` uses a type from `account`. `payment` may depend only on `shared` and `platform`.

`tally/src/main/java/com/tally/account/AccountLookup.java`
```java
package com.tally.account;

public class AccountLookup {
}
```

`tally/src/main/java/com/tally/payment/ViolationUsesAccount.java`
```java
package com.tally.payment;

import com.tally.account.AccountLookup;

/** DELIBERATE VIOLATION: payment may depend only on shared and platform. */
public class ViolationUsesAccount {
    AccountLookup lookup;
}
```

**Build output** (`./mvnw -B verify`, Maven summary):
```
[ERROR] Tests run: 2, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 1.871 s <<< FAILURE! -- in com.tally.architecture.ModuleStructureTest
[ERROR] Errors: 
[ERROR]   ModuleStructureTest.modulesRespectTheirDeclaredBoundaries:21 ? Violations - Module 'payment' depends on module 'account' via com.tally.payment.ViolationUsesAccount -> com.tally.account.AccountLookup. Allowed targets: shared, platform.
[ERROR] Tests run: 10, Failures: 0, Errors: 1, Skipped: 0
[INFO] BUILD FAILURE
```

**Failure message** (Surefire report):
```
Tests run: 2, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 1.871 s <<< FAILURE! -- in com.tally.architecture.ModuleStructureTest
com.tally.architecture.ModuleStructureTest.modulesRespectTheirDeclaredBoundaries -- Time elapsed: 0.134 s <<< ERROR!
org.springframework.modulith.core.Violations: - Module 'payment' depends on module 'account' via com.tally.payment.ViolationUsesAccount -> com.tally.account.AccountLookup. Allowed targets: shared, platform.
```

### v1b: Modulith: no access to another module's internal packages (FF-1)

**Violation.** `invoicing` may depend on `payment`, but it uses `payment.internal.AttemptRepository`, which is not in `payment`'s base package.

`tally/src/main/java/com/tally/invoicing/ViolationUsesPaymentInternals.java`
```java
package com.tally.invoicing;

import com.tally.payment.internal.AttemptRepository;

/** DELIBERATE VIOLATION: only payment's base package is its public API. */
public class ViolationUsesPaymentInternals {
    AttemptRepository attempts;
}
```

`tally/src/main/java/com/tally/payment/internal/AttemptRepository.java`
```java
package com.tally.payment.internal;

public class AttemptRepository {
}
```

**Build output** (`./mvnw -B verify`, Maven summary):
```
[ERROR] Tests run: 2, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 1.909 s <<< FAILURE! -- in com.tally.architecture.ModuleStructureTest
[ERROR] Errors: 
[ERROR]   ModuleStructureTest.modulesRespectTheirDeclaredBoundaries:21 ? Violations - Module 'invoicing' depends on non-exposed type com.tally.payment.internal.AttemptRepository within module 'payment'!
[ERROR] Tests run: 10, Failures: 0, Errors: 1, Skipped: 0
[INFO] BUILD FAILURE
```

**Failure message** (Surefire report):
```
Tests run: 2, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 1.909 s <<< FAILURE! -- in com.tally.architecture.ModuleStructureTest
com.tally.architecture.ModuleStructureTest.modulesRespectTheirDeclaredBoundaries -- Time elapsed: 0.157 s <<< ERROR!
org.springframework.modulith.core.Violations: 
- Module 'invoicing' depends on non-exposed type com.tally.payment.internal.AttemptRepository within module 'payment'!
Field <com.tally.invoicing.ViolationUsesPaymentInternals.attempts> has type <com.tally.payment.internal.AttemptRepository> in (ViolationUsesPaymentInternals.java:0)
```

### v1c: Modulith: `platform` depends on no business module (FF-1)

**Violation.** `platform` uses a type from `account`.

`tally/src/main/java/com/tally/account/AccountLookup.java`
```java
package com.tally.account;

public class AccountLookup {
}
```

`tally/src/main/java/com/tally/platform/ViolationPlatformUsesAccount.java`
```java
package com.tally.platform;

import com.tally.account.AccountLookup;

/** DELIBERATE VIOLATION: platform depends on no business module (ADR-027). */
public class ViolationPlatformUsesAccount {
    AccountLookup lookup;
}
```

**Build output** (`./mvnw -B verify`, Maven summary):
```
[ERROR] Tests run: 2, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 1.991 s <<< FAILURE! -- in com.tally.architecture.ModuleStructureTest
[ERROR] Errors: 
[ERROR]   ModuleStructureTest.modulesRespectTheirDeclaredBoundaries:21 ? Violations - Module 'platform' depends on module 'account' via com.tally.platform.ViolationPlatformUsesAccount -> com.tally.account.AccountLookup. Allowed targets: shared.
[ERROR] Tests run: 10, Failures: 0, Errors: 1, Skipped: 0
[INFO] BUILD FAILURE
```

**Failure message** (Surefire report):
```
Tests run: 2, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 1.991 s <<< FAILURE! -- in com.tally.architecture.ModuleStructureTest
com.tally.architecture.ModuleStructureTest.modulesRespectTheirDeclaredBoundaries -- Time elapsed: 0.131 s <<< ERROR!
org.springframework.modulith.core.Violations: - Module 'platform' depends on module 'account' via com.tally.platform.ViolationPlatformUsesAccount -> com.tally.account.AccountLookup. Allowed targets: shared.
```

### v1d: Modulith: no cycles between modules (FF-1)

**Violation.** `catalog` and `subscription` refer to each other.

`tally/src/main/java/com/tally/catalog/ViolationCatalogUsesSubscription.java`
```java
package com.tally.catalog;

import com.tally.subscription.SubscriptionLookup;

/** DELIBERATE VIOLATION: closes a cycle catalog -> subscription -> catalog. */
public class ViolationCatalogUsesSubscription {
    SubscriptionLookup lookup;
}
```

`tally/src/main/java/com/tally/subscription/SubscriptionLookup.java`
```java
package com.tally.subscription;

import com.tally.catalog.ViolationCatalogUsesSubscription;

public class SubscriptionLookup {
    ViolationCatalogUsesSubscription catalog;
}
```

**Build output** (`./mvnw -B verify`, Maven summary):
```
[ERROR] Tests run: 2, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 1.813 s <<< FAILURE! -- in com.tally.architecture.ModuleStructureTest
[ERROR] Errors: 
[ERROR]   ModuleStructureTest.modulesRespectTheirDeclaredBoundaries:21 ? Violations - Cycle detected: Slice catalog -> 
[ERROR] Tests run: 10, Failures: 0, Errors: 1, Skipped: 0
[INFO] BUILD FAILURE
```

**Failure message** (Surefire report):
```
Tests run: 2, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 1.813 s <<< FAILURE! -- in com.tally.architecture.ModuleStructureTest
com.tally.architecture.ModuleStructureTest.modulesRespectTheirDeclaredBoundaries -- Time elapsed: 0.137 s <<< ERROR!
org.springframework.modulith.core.Violations: 
- Cycle detected: Slice catalog -> 
                Slice subscription -> 
                Slice catalog
  1. Dependencies of Slice catalog
    - Field <com.tally.catalog.ViolationCatalogUsesSubscription.lookup> has type <com.tally.subscription.SubscriptionLookup> in (ViolationCatalogUsesSubscription.java:0)
  2. Dependencies of Slice subscription
    - Field <com.tally.subscription.SubscriptionLookup.catalog> has type <com.tally.catalog.ViolationCatalogUsesSubscription> in (SubscriptionLookup.java:0)
- Module 'catalog' depends on module 'subscription' via com.tally.catalog.ViolationCatalogUsesSubscription -> com.tally.subscription.SubscriptionLookup. Allowed targets: shared, platform, account.
```

### v1e: Top-level packages are exactly the agreed modules (FF-2)

**Violation.** A new top-level package `com.tally.billing`.

`tally/src/main/java/com/tally/billing/ViolationNewTopLevelPackage.java`
```java
package com.tally.billing;

/** DELIBERATE VIOLATION: "billing" is not an agreed module (CLAUDE.md rule 1). */
public class ViolationNewTopLevelPackage {
}
```

**Build output** (`./mvnw -B verify`, Maven summary):
```
[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 1.799 s <<< FAILURE! -- in com.tally.architecture.ModuleStructureTest
[ERROR] com.tally.architecture.ModuleStructureTest.topLevelPackagesAreExactlyTheAgreedModules -- Time elapsed: 0.084 s <<< FAILURE!
[ERROR] Failures: 
[ERROR]   ModuleStructureTest.topLevelPackagesAreExactlyTheAgreedModules:28 
[ERROR] Tests run: 10, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.6:test (default-test) on project tally: There are test failures.
```

**Failure message** (Surefire report):
```
Tests run: 2, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 1.799 s <<< FAILURE! -- in com.tally.architecture.ModuleStructureTest
com.tally.architecture.ModuleStructureTest.topLevelPackagesAreExactlyTheAgreedModules -- Time elapsed: 0.084 s <<< FAILURE!
java.lang.AssertionError: 

Expecting actual:
  ["account",
    "billing",
    "catalog",
    "entitlement",
    "invoicing",
    "payment",
    "platform",
    "shared",
    "subscription"]
to contain exactly in any order:
  ["shared",
    "platform",
    "account",
    "catalog",
    "subscription",
    "invoicing",
    "payment",
    "entitlement"]
but the following elements were unexpected:
  ["billing"]
```

### v2: No JPA relations between modules (FF-3)

**Violation.** An `invoicing` entity has `@ManyToOne Charge` and `@OneToMany List<Charge>`, where `Charge` is an entity in `payment`'s public API. Modulith accepts this, because `invoicing` may depend on `payment`, so only this rule catches it. The collection case checks that generic type arguments are inspected.

`tally/src/main/java/com/tally/invoicing/domain/ViolationInvoice.java`
```java
package com.tally.invoicing.domain;

import com.tally.payment.Charge;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import java.util.List;
import java.util.UUID;

/** DELIBERATE VIOLATION: JPA relations to another module's entity. Refer by ID. */
@Entity
public class ViolationInvoice {
    @Id
    UUID id;

    @ManyToOne
    Charge lastCharge;

    @OneToMany
    List<Charge> charges;
}
```

`tally/src/main/java/com/tally/payment/Charge.java`
```java
package com.tally.payment;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.UUID;

@Entity
public class Charge {
    @Id
    UUID id;
}
```

**Build output** (`./mvnw -B verify`, Maven summary):
```
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.294 s <<< FAILURE! -- in com.tally.architecture.ArchitectureRulesTest
[ERROR] com.tally.architecture.ArchitectureRulesTest.noJpaRelationsBetweenModules -- Time elapsed: 0.008 s <<< FAILURE!
[ERROR] Failures: 
[ERROR]   ArchitectureRulesTest.noJpaRelationsBetweenModules:67 Architecture Violation [Priority: MEDIUM] - Rule 'classes should have JPA relations only to entities of their own module, because modules refer to each other by ID only (ADR-003)' was violated (2 times):
[ERROR] Tests run: 10, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.6:test (default-test) on project tally: There are test failures.
```

**Failure message** (Surefire report):
```
Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.294 s <<< FAILURE! -- in com.tally.architecture.ArchitectureRulesTest
com.tally.architecture.ArchitectureRulesTest.noJpaRelationsBetweenModules -- Time elapsed: 0.008 s <<< FAILURE!
java.lang.AssertionError: 
Architecture Violation [Priority: MEDIUM] - Rule 'classes should have JPA relations only to entities of their own module, because modules refer to each other by ID only (ADR-003)' was violated (2 times):
com.tally.invoicing.domain.ViolationInvoice.charges has a JPA relation to com.tally.payment.Charge in module 'payment' (refer by ID instead)
com.tally.invoicing.domain.ViolationInvoice.lastCharge has a JPA relation to com.tally.payment.Charge in module 'payment' (refer by ID instead)
```

### v3: No direct `now()` or system clock (FF-4)

**Violation.** `Instant.now()`, `LocalDate.now()`, `System.currentTimeMillis()` and `Clock.systemUTC()` in `account`.

`tally/src/main/java/com/tally/account/ViolationReadsSystemClock.java`
```java
package com.tally.account;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

/** DELIBERATE VIOLATION: reads the system clock instead of the injected Clock (D4). */
public class ViolationReadsSystemClock {
    Instant a() { return Instant.now(); }
    LocalDate b() { return LocalDate.now(); }
    long c() { return System.currentTimeMillis(); }
    Clock d() { return Clock.systemUTC(); }
}
```

**Build output** (`./mvnw -B verify`, Maven summary):
```
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.386 s <<< FAILURE! -- in com.tally.architecture.ArchitectureRulesTest
[ERROR] com.tally.architecture.ArchitectureRulesTest.noDirectReadsOfTheSystemClock -- Time elapsed: 0.015 s <<< FAILURE!
[ERROR] Failures: 
[ERROR]   ArchitectureRulesTest.noDirectReadsOfTheSystemClock:81 Architecture Violation [Priority: MEDIUM] - Rule 'no classes that are outside com.tally.platform.ClockConfiguration should call method where a no-argument now() on a java.time type or should call method System.currentTimeMillis() or should call method where Clock.systemUTC(), Clock.systemDefaultZone() or Clock.system(zone), because time must come from the injected java.time.Clock (D4)' was violated (4 times):
[ERROR] Tests run: 10, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.6:test (default-test) on project tally: There are test failures.
```

**Failure message** (Surefire report):
```
Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.386 s <<< FAILURE! -- in com.tally.architecture.ArchitectureRulesTest
com.tally.architecture.ArchitectureRulesTest.noDirectReadsOfTheSystemClock -- Time elapsed: 0.015 s <<< FAILURE!
java.lang.AssertionError: 
Architecture Violation [Priority: MEDIUM] - Rule 'no classes that are outside com.tally.platform.ClockConfiguration should call method where a no-argument now() on a java.time type or should call method System.currentTimeMillis() or should call method where Clock.systemUTC(), Clock.systemDefaultZone() or Clock.system(zone), because time must come from the injected java.time.Clock (D4)' was violated (4 times):
Method <com.tally.account.ViolationReadsSystemClock.a()> calls method <java.time.Instant.now()> in (ViolationReadsSystemClock.java:9)
Method <com.tally.account.ViolationReadsSystemClock.b()> calls method <java.time.LocalDate.now()> in (ViolationReadsSystemClock.java:10)
Method <com.tally.account.ViolationReadsSystemClock.c()> calls method <java.lang.System.currentTimeMillis()> in (ViolationReadsSystemClock.java:11)
Method <com.tally.account.ViolationReadsSystemClock.d()> calls method <java.time.Clock.systemUTC()> in (ViolationReadsSystemClock.java:12)
```

### v4: No `@Scheduled` outside `platform` (FF-5)

**Violation.** A `@Scheduled` method in `invoicing`. The class also carries `@EnableScheduling`, but the first failing check stops the test, so that part is proven separately in v4b.

`tally/src/main/java/com/tally/invoicing/ViolationScheduler.java`
```java
package com.tally.invoicing;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** DELIBERATE VIOLATION: scheduled work belongs on the durable queues (ADR-017). */
@EnableScheduling
public class ViolationScheduler {
    @Scheduled(fixedDelay = 60_000)
    void renewDueSubscriptions() {
    }
}
```

**Build output** (`./mvnw -B verify`, Maven summary):
```
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.257 s <<< FAILURE! -- in com.tally.architecture.ArchitectureRulesTest
[ERROR] com.tally.architecture.ArchitectureRulesTest.noSchedulingOutsidePlatform -- Time elapsed: 0.016 s <<< FAILURE!
[ERROR] Failures: 
[ERROR]   ArchitectureRulesTest.noSchedulingOutsidePlatform:100 Architecture Violation [Priority: MEDIUM] - Rule 'methods that are annotated with @Scheduled or are annotated with @Schedules should be declared in classes that reside in a package 'com.tally.platform..', because scheduled work runs on the durable queues (ADR-017)' was violated (1 times):
[ERROR] Tests run: 10, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.6:test (default-test) on project tally: There are test failures.
```

**Failure message** (Surefire report):
```
Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.257 s <<< FAILURE! -- in com.tally.architecture.ArchitectureRulesTest
com.tally.architecture.ArchitectureRulesTest.noSchedulingOutsidePlatform -- Time elapsed: 0.016 s <<< FAILURE!
java.lang.AssertionError: 
Architecture Violation [Priority: MEDIUM] - Rule 'methods that are annotated with @Scheduled or are annotated with @Schedules should be declared in classes that reside in a package 'com.tally.platform..', because scheduled work runs on the durable queues (ADR-017)' was violated (1 times):
Method <com.tally.invoicing.ViolationScheduler.renewDueSubscriptions()> is not declared in classes that reside in a package 'com.tally.platform..' in (ViolationScheduler.java:11)
```

### v4b: No `@EnableScheduling` outside `platform` (FF-5)

**Violation.** `@EnableScheduling` on a configuration class in `entitlement`.

`tally/src/main/java/com/tally/entitlement/ViolationEnablesScheduling.java`
```java
package com.tally.entitlement;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** DELIBERATE VIOLATION: switches on Spring's scheduler outside platform (ADR-017). */
@Configuration
@EnableScheduling
public class ViolationEnablesScheduling {
}
```

**Build output** (`./mvnw -B verify`, Maven summary):
```
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.315 s <<< FAILURE! -- in com.tally.architecture.ArchitectureRulesTest
[ERROR] com.tally.architecture.ArchitectureRulesTest.noSchedulingOutsidePlatform -- Time elapsed: 0.019 s <<< FAILURE!
[ERROR] Failures: 
[ERROR]   ArchitectureRulesTest.noSchedulingOutsidePlatform:101 Architecture Violation [Priority: MEDIUM] - Rule 'no classes that reside outside of package 'com.tally.platform..' should be annotated with @EnableScheduling, because scheduled work runs on the durable queues (ADR-017)' was violated (1 times):
[ERROR] Tests run: 10, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.6:test (default-test) on project tally: There are test failures.
```

**Failure message** (Surefire report):
```
Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.315 s <<< FAILURE! -- in com.tally.architecture.ArchitectureRulesTest
com.tally.architecture.ArchitectureRulesTest.noSchedulingOutsidePlatform -- Time elapsed: 0.019 s <<< FAILURE!
java.lang.AssertionError: 
Architecture Violation [Priority: MEDIUM] - Rule 'no classes that reside outside of package 'com.tally.platform..' should be annotated with @EnableScheduling, because scheduled work runs on the durable queues (ADR-017)' was violated (1 times):
Class <com.tally.entitlement.ViolationEnablesScheduling> is annotated with @EnableScheduling in (ViolationEnablesScheduling.java:0)
```

### v5: No thread or executor creation outside `platform` (FF-6)

**Violation.** `Executors.newFixedThreadPool`, `new Thread`, `Thread.ofVirtual()` and `new ThreadPoolExecutor` in `payment`.

`tally/src/main/java/com/tally/payment/ViolationOwnThreads.java`
```java
package com.tally.payment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

/** DELIBERATE VIOLATION: only the platform owns background threads (ADR-027). */
public class ViolationOwnThreads {
    ExecutorService a() { return Executors.newFixedThreadPool(15); }
    Thread b() { return new Thread(() -> { }); }
    Thread c() { return Thread.ofVirtual().unstarted(() -> { }); }
    ThreadPoolExecutor d() {
        return new ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>());
    }
}
```

**Build output** (`./mvnw -B verify`, Maven summary):
```
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.330 s <<< FAILURE! -- in com.tally.architecture.ArchitectureRulesTest
[ERROR] com.tally.architecture.ArchitectureRulesTest.noThreadOrExecutorCreationOutsidePlatform -- Time elapsed: 0.013 s <<< FAILURE!
[ERROR] Failures: 
[ERROR]   ArchitectureRulesTest.noThreadOrExecutorCreationOutsidePlatform:115 Architecture Violation [Priority: MEDIUM] - Rule 'no classes that reside outside of package 'com.tally.platform..' should depend on classes that are assignable to java.util.concurrent.Executors or should call constructor where a constructor of a Thread, an Executor or a Spring TaskScheduler or should call method where Thread.ofVirtual(), Thread.ofPlatform() or Thread.startVirtualThread(), because only the platform creates threads for background work (ADR-027)' was violated (4 times):
[ERROR] Tests run: 10, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.6:test (default-test) on project tally: There are test failures.
```

**Failure message** (Surefire report):
```
Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.330 s <<< FAILURE! -- in com.tally.architecture.ArchitectureRulesTest
com.tally.architecture.ArchitectureRulesTest.noThreadOrExecutorCreationOutsidePlatform -- Time elapsed: 0.013 s <<< FAILURE!
java.lang.AssertionError: 
Architecture Violation [Priority: MEDIUM] - Rule 'no classes that reside outside of package 'com.tally.platform..' should depend on classes that are assignable to java.util.concurrent.Executors or should call constructor where a constructor of a Thread, an Executor or a Spring TaskScheduler or should call method where Thread.ofVirtual(), Thread.ofPlatform() or Thread.startVirtualThread(), because only the platform creates threads for background work (ADR-027)' was violated (4 times):
Method <com.tally.payment.ViolationOwnThreads.a()> calls method <java.util.concurrent.Executors.newFixedThreadPool(int)> in (ViolationOwnThreads.java:9)
Method <com.tally.payment.ViolationOwnThreads.b()> calls constructor <java.lang.Thread.<init>(java.lang.Runnable)> in (ViolationOwnThreads.java:10)
Method <com.tally.payment.ViolationOwnThreads.c()> calls method <java.lang.Thread.ofVirtual()> in (ViolationOwnThreads.java:11)
Method <com.tally.payment.ViolationOwnThreads.d()> calls constructor <java.util.concurrent.ThreadPoolExecutor.<init>(int, int, long, java.util.concurrent.TimeUnit, java.util.concurrent.BlockingQueue)> in (ViolationOwnThreads.java:13)
```

### v6: Nothing in `payment` imports `invoicing` (FF-7)

**Violation.** A `payment` class holds an `invoicing` type. Modulith verification fails as well, because `invoicing` is not among `payment`'s allowed dependencies. The two guards overlap on purpose: the ArchUnit rule still holds if someone widens `payment`'s `allowedDependencies`.

`tally/src/main/java/com/tally/invoicing/InvoiceNumber.java`
```java
package com.tally.invoicing;

public record InvoiceNumber(String value) {
}
```

`tally/src/main/java/com/tally/payment/ViolationKnowsInvoices.java`
```java
package com.tally.payment;

import com.tally.invoicing.InvoiceNumber;

/** DELIBERATE VIOLATION: payment knows nothing about invoices (ADR-014). */
public class ViolationKnowsInvoices {
    InvoiceNumber invoice;
}
```

**Build output** (`./mvnw -B verify`, Maven summary):
```
[ERROR] Tests run: 2, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 1.995 s <<< FAILURE! -- in com.tally.architecture.ModuleStructureTest
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.260 s <<< FAILURE! -- in com.tally.architecture.ArchitectureRulesTest
[ERROR] com.tally.architecture.ArchitectureRulesTest.paymentDoesNotDependOnInvoicing -- Time elapsed: 0.019 s <<< FAILURE!
[ERROR] Failures: 
[ERROR]   ArchitectureRulesTest.paymentDoesNotDependOnInvoicing:127 Architecture Violation [Priority: MEDIUM] - Rule 'no classes that reside in a package 'com.tally.payment..' should depend on classes that reside in a package 'com.tally.invoicing..', because payment knows nothing about invoices (ADR-014)' was violated (1 times):
[ERROR] Errors: 
[ERROR]   ModuleStructureTest.modulesRespectTheirDeclaredBoundaries:21 ? Violations - Module 'payment' depends on module 'invoicing' via com.tally.payment.ViolationKnowsInvoices -> com.tally.invoicing.InvoiceNumber. Allowed targets: shared, platform.
[ERROR] Tests run: 10, Failures: 1, Errors: 1, Skipped: 0
[INFO] BUILD FAILURE
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.6:test (default-test) on project tally: There are test failures.
```

**Failure message** (Surefire report):
```
Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.260 s <<< FAILURE! -- in com.tally.architecture.ArchitectureRulesTest
com.tally.architecture.ArchitectureRulesTest.paymentDoesNotDependOnInvoicing -- Time elapsed: 0.019 s <<< FAILURE!
java.lang.AssertionError: 
Architecture Violation [Priority: MEDIUM] - Rule 'no classes that reside in a package 'com.tally.payment..' should depend on classes that reside in a package 'com.tally.invoicing..', because payment knows nothing about invoices (ADR-014)' was violated (1 times):
Field <com.tally.payment.ViolationKnowsInvoices.invoice> has type <com.tally.invoicing.InvoiceNumber> in (ViolationKnowsInvoices.java:0)
Tests run: 2, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 1.995 s <<< FAILURE! -- in com.tally.architecture.ModuleStructureTest
com.tally.architecture.ModuleStructureTest.modulesRespectTheirDeclaredBoundaries -- Time elapsed: 0.123 s <<< ERROR!
org.springframework.modulith.core.Violations: - Module 'payment' depends on module 'invoicing' via com.tally.payment.ViolationKnowsInvoices -> com.tally.invoicing.InvoiceNumber. Allowed targets: shared, platform.
```

### v7: `domain` does not depend on adapters or web (FF-8)

**Violation.** `catalog.domain` uses `catalog.adapter.web.PlanController` and returns Spring's `ResponseEntity`. Both are in the same module, so Modulith accepts it and only this rule catches it.

`tally/src/main/java/com/tally/catalog/adapter/web/PlanController.java`
```java
package com.tally.catalog.adapter.web;

public class PlanController {
}
```

`tally/src/main/java/com/tally/catalog/domain/ViolationPlan.java`
```java
package com.tally.catalog.domain;

import com.tally.catalog.adapter.web.PlanController;
import org.springframework.http.ResponseEntity;

/** DELIBERATE VIOLATION: the domain must not know its adapters or the web (ADR-003). */
public class ViolationPlan {
    PlanController controller;

    ResponseEntity<String> render() {
        return ResponseEntity.ok("plan");
    }
}
```

**Build output** (`./mvnw -B verify`, Maven summary):
```
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.335 s <<< FAILURE! -- in com.tally.architecture.ArchitectureRulesTest
[ERROR] com.tally.architecture.ArchitectureRulesTest.domainDoesNotDependOnAdapters -- Time elapsed: 0.009 s <<< FAILURE!
[ERROR] Failures: 
[ERROR]   ArchitectureRulesTest.domainDoesNotDependOnAdapters:143 Architecture Violation [Priority: MEDIUM] - Rule 'no classes that reside in a package 'com.tally.*.domain..' should depend on classes that reside in any package ['com.tally.*.adapter..', 'org.springframework.web..', 'org.springframework.http..', 'jakarta.servlet..'], because domain packages do not depend on web or adapter packages (ADR-003)' was violated (3 times):
[ERROR] Tests run: 10, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.6:test (default-test) on project tally: There are test failures.
```

**Failure message** (Surefire report):
```
Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.335 s <<< FAILURE! -- in com.tally.architecture.ArchitectureRulesTest
com.tally.architecture.ArchitectureRulesTest.domainDoesNotDependOnAdapters -- Time elapsed: 0.009 s <<< FAILURE!
java.lang.AssertionError: 
Architecture Violation [Priority: MEDIUM] - Rule 'no classes that reside in a package 'com.tally.*.domain..' should depend on classes that reside in any package ['com.tally.*.adapter..', 'org.springframework.web..', 'org.springframework.http..', 'jakarta.servlet..'], because domain packages do not depend on web or adapter packages (ADR-003)' was violated (3 times):
Field <com.tally.catalog.domain.ViolationPlan.controller> has type <com.tally.catalog.adapter.web.PlanController> in (ViolationPlan.java:0)
Method <com.tally.catalog.domain.ViolationPlan.render()> calls method <org.springframework.http.ResponseEntity.ok(java.lang.Object)> in (ViolationPlan.java:11)
Method <com.tally.catalog.domain.ViolationPlan.render()> has return type <org.springframework.http.ResponseEntity> in (ViolationPlan.java:0)
```

### v8a: Pinned event classes: a new event must be pinned (FF-9)

**Violation.** A new `DomainEvent`, `com.tally.subscription.SubscriptionChanged`, that is not in `pinned-events.txt`.

`tally/src/main/java/com/tally/subscription/SubscriptionChanged.java`
```java
package com.tally.subscription;

import com.tally.shared.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** DELIBERATE VIOLATION: a new event class that is not pinned. */
public record SubscriptionChanged(UUID eventId, Instant occurredAt, UUID accountId) implements DomainEvent {
}
```

**Build output** (`./mvnw -B verify`, Maven summary):
```
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.277 s <<< FAILURE! -- in com.tally.architecture.PinnedEventClassesTest
[ERROR] com.tally.architecture.PinnedEventClassesTest.eventClassesMatchThePinnedList -- Time elapsed: 0.270 s <<< FAILURE!
[ERROR] Failures: 
[ERROR]   PinnedEventClassesTest.eventClassesMatchThePinnedList:41 [Event classes missing from architecture/pinned-events.txt. Pin every new event (ADR-009)] 
[ERROR] Tests run: 10, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.6:test (default-test) on project tally: There are test failures.
```

**Failure message** (Surefire report):
```
Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.277 s <<< FAILURE! -- in com.tally.architecture.PinnedEventClassesTest
com.tally.architecture.PinnedEventClassesTest.eventClassesMatchThePinnedList -- Time elapsed: 0.270 s <<< FAILURE!
java.lang.AssertionError: 
[Event classes missing from architecture/pinned-events.txt. Pin every new event (ADR-009)] 
Expecting :
  ["com.tally.subscription.SubscriptionChanged"]
to be subset of
  []
but found these extra elements:
  ["com.tally.subscription.SubscriptionChanged"]
```

### v8b: Pinned event classes: an event cannot be moved or renamed (FF-9)

**Violation.** `com.tally.subscription.SubscriptionChanged` is pinned, but the class has moved to `com.tally.subscription.events`.

`tally/src/main/java/com/tally/subscription/events/SubscriptionChanged.java`
```java
package com.tally.subscription.events;

import com.tally.shared.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** DELIBERATE VIOLATION: a pinned event moved from com.tally.subscription to a subpackage. */
public record SubscriptionChanged(UUID eventId, Instant occurredAt, UUID accountId) implements DomainEvent {
}
```

`tally/src/test/resources/architecture/pinned-events.txt` (non-comment lines)
```
com.tally.subscription.SubscriptionChanged
```

**Build output** (`./mvnw -B verify`, Maven summary):
```
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.292 s <<< FAILURE! -- in com.tally.architecture.PinnedEventClassesTest
[ERROR] com.tally.architecture.PinnedEventClassesTest.eventClassesMatchThePinnedList -- Time elapsed: 0.277 s <<< FAILURE!
[ERROR] Failures: 
[ERROR]   PinnedEventClassesTest.eventClassesMatchThePinnedList:38 [Pinned event classes that no longer exist. Event classes are never renamed or moved (ADR-009)] 
[ERROR] Tests run: 10, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.6:test (default-test) on project tally: There are test failures.
```

**Failure message** (Surefire report):
```
Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.292 s <<< FAILURE! -- in com.tally.architecture.PinnedEventClassesTest
com.tally.architecture.PinnedEventClassesTest.eventClassesMatchThePinnedList -- Time elapsed: 0.277 s <<< FAILURE!
java.lang.AssertionError: 
[Pinned event classes that no longer exist. Event classes are never renamed or moved (ADR-009)] 
Expecting :
  ["com.tally.subscription.SubscriptionChanged"]
to be subset of
  ["com.tally.subscription.events.SubscriptionChanged"]
but found these extra elements:
  ["com.tally.subscription.SubscriptionChanged"]
```

### v9: Every background job has a health signal (FF-10)

**Violation.** A `BackgroundJob` in `platform` that does not implement `HealthSignal`.

`tally/src/main/java/com/tally/platform/ViolationSilentJob.java`
```java
package com.tally.platform;

/** DELIBERATE VIOLATION: a background job with no health signal (ADR-023). */
public class ViolationSilentJob implements BackgroundJob {
    @Override
    public String name() {
        return "silent";
    }
}
```

**Build output** (`./mvnw -B verify`, Maven summary):
```
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.283 s <<< FAILURE! -- in com.tally.architecture.ArchitectureRulesTest
[ERROR] com.tally.architecture.ArchitectureRulesTest.everyBackgroundJobReportsHealth -- Time elapsed: 0.007 s <<< FAILURE!
[ERROR] Failures: 
[ERROR]   ArchitectureRulesTest.everyBackgroundJobReportsHealth:156 Architecture Violation [Priority: MEDIUM] - Rule 'classes that implement com.tally.platform.BackgroundJob and are not interfaces should implement com.tally.platform.HealthSignal, because every background job reports health (ADR-023)' was violated (1 times):
[ERROR] Tests run: 10, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.6:test (default-test) on project tally: There are test failures.
```

**Failure message** (Surefire report):
```
Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.283 s <<< FAILURE! -- in com.tally.architecture.ArchitectureRulesTest
com.tally.architecture.ArchitectureRulesTest.everyBackgroundJobReportsHealth -- Time elapsed: 0.007 s <<< FAILURE!
java.lang.AssertionError: 
Architecture Violation [Priority: MEDIUM] - Rule 'classes that implement com.tally.platform.BackgroundJob and are not interfaces should implement com.tally.platform.HealthSignal, because every background job reports health (ADR-023)' was violated (1 times):
Class <com.tally.platform.ViolationSilentJob> does not implement com.tally.platform.HealthSignal in (ViolationSilentJob.java:0)
```

## After removing every violation

`./mvnw -B verify` on the clean tree:

```
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.826 s -- in com.tally.architecture.ModuleStructureTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.283 s -- in com.tally.architecture.PinnedEventClassesTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.237 s -- in com.tally.architecture.ArchitectureRulesTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 9.603 s -- in com.tally.TallyApplicationIT
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.672 s -- in com.fakeprovider.FakeProviderApplicationTests
[INFO] Reactor Summary for Tally (parent) 0.1.0-SNAPSHOT:
[INFO] Tally (parent) ..................................... SUCCESS [  0.002 s]
[INFO] Tally .............................................. SUCCESS [ 18.018 s]
[INFO] Fake payment provider .............................. SUCCESS [  3.595 s]
[INFO] BUILD SUCCESS
```

## Notes for review

- **Rules that overlap.** v6 fails both the ArchUnit rule and Modulith verification. v1d's cycle is also reported as an undeclared dependency. Both overlaps are expected: a cycle with only declared dependencies is impossible, because the declared graph has no cycles.
- **Empty modules.** The ArchUnit rules use `allowEmptyShould(true)`, because most modules have no classes yet. Without it, ArchUnit would fail every rule whose subject set is empty. Revisit this once each module has code.
- **The clock exception** is a single class name, `com.tally.platform.ClockConfiguration` (and its nested classes), which step 4 will create. Only the no-argument `now()` is banned: `Instant.now(clock)` stays allowed. `System.nanoTime()` is allowed, for measuring durations.
- **The job health rule is structural for now.** No job exists until step 4. The rule requires every concrete `platform.BackgroundJob` to implement `platform.HealthSignal`, which has `lastSuccess()` and `waitingWork()` (ADR-023). Step 4 should add a runtime check that each registered job and queue exposes its `tally.job.*` and `tally.queue.*` metrics.
- **A failure during the work.** The first attempt at these runs used a script with relative paths that broke after a `cd`. Its 14 builds failed, but the violation files were not removed afterwards. Before anything was committed, those files were deleted, the pinned list was restored, and `./mvnw verify` was confirmed green. Every run recorded above comes from the corrected script, starting from a clean tree.

## Decisions taken in steps 1 and 2 that need confirmation

These were proposed in the plan and built with the recommended choice. Each is easy to change.

1. **`catalog` may depend on `account`.** `CatalogApi.quote(accountId, planId, period)` needs the account's currency. The alternative is a quote that takes a currency, which changes the brief's signature.
2. **Job health is a structural rule for now** (FF-10, above), with a runtime metric check to follow in step 4.
3. **`DomainEvent.accountId()` is a `UUID`.** No account ID type exists yet. If account IDs become a value type, this changes before any event is published.
4. **No explicit ArchUnit version.** Spring Boot doesn't manage ArchUnit, so ADR-026's rule ("versions pinned through Spring Boot's dependency management") can't cover it. Instead of pinning a version, ArchUnit 1.4.2 comes in through `spring-modulith-starter-test`: the version Spring Modulith 2.1.1 is built against, and one that supports Java 25. The rules use ArchUnit's core API directly, so `archunit-junit5` isn't needed.

## Added in build step 4: FF-10b, job health at runtime

Step 2's FF-10 is static: a class implementing `BackgroundJob` must also implement `HealthSignal`. Step 4 adds the runtime half that was promised then. `EveryJobHasHealthMetricsIT` starts the application with polling switched on. It checks that every job the platform registered (queue pollers, reapers, periodic jobs) and every `BackgroundJob` bean has a `tally.job.last_success_age_seconds` gauge, and that every registered queue has `tally.queue.oldest_due_age_seconds` and `tally.queue.waiting`.

| Case | Fitness function | Guards | Failing test | Result |
|---|---|---|---|---|
| v10b | FF-10b: every running job and queue has its health metrics | ADR-023 | `EveryJobHasHealthMetricsIT.everyJobAndQueueHasItsHealthMetrics` | fails as expected (exit 1) |

Run against commit `84a4254` ("Slice 1 step 4: health metrics for every queue and job"), using the same script as above, which now also reads the Failsafe reports.

### v10b: a job outside the platform has no health metric (FF-10b)

**Violation.** A bean in `account` implements `BackgroundJob` and `HealthSignal`, so it passes FF-10. But it is not registered with the platform, so no metric reports it. Only the runtime check catches this.

`tally/src/main/java/com/tally/account/ViolationUnmonitoredJob.java`
```java
package com.tally.account;

import com.tally.platform.BackgroundJob;
import com.tally.platform.HealthSignal;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * DELIBERATE VIOLATION: a background job that runs outside the platform's registry, so no
 * health metric is ever registered for it (ADR-023). It satisfies the static rule (FF-10).
 */
@Component
public class ViolationUnmonitoredJob implements BackgroundJob, HealthSignal {

    @Override
    public String name() {
        return "account-cleanup";
    }

    @Override
    public Optional<Instant> lastSuccess() {
        return Optional.empty();
    }

    @Override
    public long waitingWork() {
        return 0;
    }
}
```

**Build output** (`./mvnw -B verify`, Maven summary):
```
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 9.569 s <<< FAILURE! -- in com.tally.platform.internal.EveryJobHasHealthMetricsIT
[ERROR] com.tally.platform.internal.EveryJobHasHealthMetricsIT.everyJobAndQueueHasItsHealthMetrics -- Time elapsed: 0.697 s <<< FAILURE!
[ERROR] Failures: 
[ERROR]   EveryJobHasHealthMetricsIT.everyJobAndQueueHasItsHealthMetrics:59 [Every background job and queue reports health (ADR-023)] 
[ERROR] Tests run: 20, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-failsafe-plugin:3.5.6:verify (default) on project tally: There are test failures.
```

**Failure message** (Failsafe report):
```
Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 9.569 s <<< FAILURE! -- in com.tally.platform.internal.EveryJobHasHealthMetricsIT
com.tally.platform.internal.EveryJobHasHealthMetricsIT.everyJobAndQueueHasItsHealthMetrics -- Time elapsed: 0.697 s <<< FAILURE!
java.lang.AssertionError: 
[Every background job and queue reports health (ADR-023)] 
Expecting empty but was: ["job account-cleanup: tally.job.last_success_age_seconds"]
```

After the violation was removed, `./mvnw -B verify` was green again (see the step 4 commit that adds this section).

Until step 5 adds the first production queue, FF-10b passes on the main application with no production jobs to check. Its mechanism is covered by `HealthMetricsIT` and `QueuePollingIT`, which register test queues and jobs.

## Added after slice 1: FF-11, the pinned dependency graph, and no empty rules

**FF-11.** `architecture.PinnedModuleDependenciesTest` compares every module's `@ApplicationModule(allowedDependencies)` with the approved graph in `tally/src/test/resources/architecture/module-dependencies.txt`. FF-1 only checks that code keeps to the declarations; a declaration widened without approval would pass FF-1. FF-11 catches it.

**`allowEmptyShould` is off on every ArchUnit rule.** A rule whose `that()` clause matches nothing now fails, so a rule cannot pass silently after its subject is renamed or moved. FF-5's method rule was rewritten as "no method outside `platform` is annotated `@Scheduled`" because there are no `@Scheduled` methods anywhere, and the old form ("methods annotated `@Scheduled` reside in `platform`") would have had an empty subject.

### v11: a module declares a dependency that is not approved (FF-11)

**Violation.** `payment/package-info.java` declares `allowedDependencies = { "shared", "platform", "account" }`. No code uses `account`, so FF-1 still passes.

**Build output** (`./mvnw -B -pl tally test -Dtest='PinnedModuleDependenciesTest,ModuleStructureTest'`):
```
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.795 s -- in com.tally.architecture.ModuleStructureTest
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.099 s <<< FAILURE! -- in com.tally.architecture.PinnedModuleDependenciesTest
expected: {"account"=["platform", "shared"], "catalog"=["account", "platform", "shared"], "entitlement"=["payment", "platform", "shared", "subscription"], "invoicing"=["account", "catalog", "payment", "platform", "shared", "subscription"], "payment"=["platform", "shared"], "platform"=["shared"], "shared"=[], "subscription"=["account", "catalog", "platform", "shared"]}
 but was: {"account"=["platform", "shared"], "catalog"=["account", "platform", "shared"], "entitlement"=["payment", "platform", "shared", "subscription"], "invoicing"=["account", "catalog", "payment", "platform", "shared", "subscription"], "payment"=["account", "platform", "shared"], "platform"=["shared"], "shared"=[], "subscription"=["account", "catalog", "platform", "shared"]}
```
FF-1 (`ModuleStructureTest`) passed; FF-11 failed on `"payment"=["account", "platform", "shared"]`.

### v12: a rule whose subject matches no classes (`allowEmptyShould` off)

**Violation.** FF-7's subject was changed to the misspelt package `com.tally.payments..`, which has no classes. With `allowEmptyShould(true)` this rule passed.

**Build output** (`./mvnw -B -pl tally test -Dtest='ArchitectureRulesTest'`):
```
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 1.301 s <<< FAILURE! -- in com.tally.architecture.ArchitectureRulesTest
java.lang.AssertionError: Rule 'no classes that reside in a package 'com.tally.payments..' should depend on classes that reside in a package 'com.tally.invoicing..', because payment knows nothing about invoices (ADR-014)' failed to check any classes. This means either that no classes have been passed to the rule at all, or that no classes passed to the rule matched the `that()` clause. To allow rules being evaluated without checking any classes you can either use `ArchRule.allowEmptyShould(true)` on a single rule or set the configuration property `archRule.failOnEmptyShould = false` to change the behavior globally.
```

After both violations were removed, `./mvnw -B verify` was green (the commit that adds this section).
