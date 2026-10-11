package com.tally.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tally.platform.BackgroundJob;
import com.tally.platform.HealthSignal;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import java.lang.annotation.Annotation;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;

/**
 * ArchUnit fitness functions from slice 1, build step 2. Each rule names the record it guards.
 * Every rule fails if it matches no classes ({@code allowEmptyShould} is off), so a rule whose
 * subject has been renamed or moved away cannot pass silently. FF-8 ({@code domainDoesNotDependOnAdapters}) was
 * retired at the Slice 1R close-out: F9 and F11 cover every domain package between them.
 */
class ArchitectureRulesTest {

    static final String ROOT = "com.tally";
    static final String PLATFORM = "com.tally.platform..";

    /** The one class allowed to read the system clock (D4). Created with the platform in step 4. */
    static final String CLOCK_CONFIGURATION = "com.tally.platform.ClockConfiguration";

    static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages(ROOT);

    /** FR-001, FR-003: modules refer to each other's aggregates by ID only. */
    @Test
    void noJpaRelationsBetweenModules() {
        List<Class<? extends Annotation>> relations =
                List.of(ManyToOne.class, OneToMany.class, OneToOne.class, ManyToMany.class);

        ArchRule rule = classes()
                .should(haveJpaRelationsOnlyWithinTheirModule(relations))
                .because("modules refer to each other by ID only (FR-003)");

        rule.check(CLASSES);
    }

    /** D4, FR-003: time comes from the injected Clock, so tests can move it. */
    @Test
    void noDirectReadsOfTheSystemClock() {
        ArchRule rule = noClasses()
                .that(outsideTheClockConfiguration())
                .should().callMethodWhere(isNoArgumentNowOnJavaTime())
                .orShould().callMethod(System.class, "currentTimeMillis")
                .orShould().callMethodWhere(readsTheSystemClockThroughClock())
                .because("time must come from the injected java.time.Clock (D4)");

        rule.check(CLASSES);
    }

    /** FR-017, FR-027: scheduling belongs to the platform's queue pollers. */
    @Test
    void noSchedulingOutsidePlatform() {
        ArchRule scheduledMethods = noMethods()
                .that().areDeclaredInClassesThat().resideOutsideOfPackage(PLATFORM)
                .should().beAnnotatedWith(Scheduled.class)
                .orShould().beAnnotatedWith(Schedules.class)
                .because("scheduled work runs on the durable queues (FR-017)");

        ArchRule enableScheduling = noClasses()
                .that().resideOutsideOfPackage(PLATFORM)
                .should().beAnnotatedWith(EnableScheduling.class)
                .because("scheduled work runs on the durable queues (FR-017)");

        scheduledMethods.check(CLASSES);
        enableScheduling.check(CLASSES);
    }

    /** FR-027: one place owns threads for background work. */
    @Test
    void noThreadOrExecutorCreationOutsidePlatform() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage(PLATFORM)
                .should().dependOnClassesThat().areAssignableTo(Executors.class)
                .orShould().callConstructorWhere(createsAThreadOrExecutor())
                .orShould().callMethodWhere(startsAThreadThroughThreadBuilders())
                .because("only the platform creates threads for background work (FR-027)");

        rule.check(CLASSES);
    }

    /** FR-014: Payment accepts an amount and an opaque reference, and knows nothing about invoices. */
    @Test
    void paymentDoesNotDependOnInvoicing() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.tally.payment..")
                .should().dependOnClassesThat().resideInAPackage("com.tally.invoicing..")
                .because("payment knows nothing about invoices (FR-014)");

        rule.check(CLASSES);
    }

    /** FR-023: every background job reports its last success and its waiting work. */
    @Test
    void everyBackgroundJobReportsHealth() {
        ArchRule rule = classes()
                .that().implement(BackgroundJob.class)
                .and().areNotInterfaces()
                .should().implement(HealthSignal.class)
                .because("every background job reports health (FR-023)");

        rule.check(CLASSES);
    }

    // --- Predicates and conditions -------------------------------------------------------------

    private static DescribedPredicate<JavaClass> outsideTheClockConfiguration() {
        return DescribedPredicate.describe("are outside " + CLOCK_CONFIGURATION,
                c -> !c.getName().equals(CLOCK_CONFIGURATION)
                        && !c.getName().startsWith(CLOCK_CONFIGURATION + "$"));
    }

    private static DescribedPredicate<JavaMethodCall> isNoArgumentNowOnJavaTime() {
        return DescribedPredicate.describe("a no-argument now() on a java.time type",
                call -> call.getName().equals("now")
                        && call.getTarget().getParameterTypes().isEmpty()
                        && call.getTargetOwner().getPackageName().equals("java.time"));
    }

    private static DescribedPredicate<JavaMethodCall> readsTheSystemClockThroughClock() {
        Set<String> systemClocks = Set.of("systemUTC", "systemDefaultZone", "system");
        return DescribedPredicate.describe("Clock.systemUTC(), Clock.systemDefaultZone() or Clock.system(zone)",
                call -> call.getTargetOwner().isEquivalentTo(Clock.class)
                        && systemClocks.contains(call.getName()));
    }

    private static DescribedPredicate<JavaConstructorCall> createsAThreadOrExecutor() {
        return DescribedPredicate.describe("a constructor of a Thread, an Executor or a Spring TaskScheduler",
                call -> {
                    JavaClass owner = call.getTargetOwner();
                    return owner.isAssignableTo(Thread.class)
                            || owner.isAssignableTo(Executor.class)
                            || owner.isAssignableTo("org.springframework.scheduling.TaskScheduler");
                });
    }

    private static DescribedPredicate<JavaMethodCall> startsAThreadThroughThreadBuilders() {
        Set<String> factories = Set.of("ofVirtual", "ofPlatform", "startVirtualThread");
        return DescribedPredicate.describe("Thread.ofVirtual(), Thread.ofPlatform() or Thread.startVirtualThread()",
                call -> call.getTargetOwner().isEquivalentTo(Thread.class)
                        && factories.contains(call.getName()));
    }

    private static ArchCondition<JavaClass> haveJpaRelationsOnlyWithinTheirModule(
            List<Class<? extends Annotation>> relations) {
        return new ArchCondition<>("have JPA relations only to entities of their own module") {
            @Override
            public void check(JavaClass entity, ConditionEvents events) {
                String module = moduleOf(entity);
                List<JavaMember> members = new ArrayList<>(entity.getFields());
                members.addAll(entity.getMethods());
                for (JavaMember member : members) {
                    if (relations.stream().noneMatch(member::isAnnotatedWith)) {
                        continue;
                    }
                    Set<JavaClass> involved = member instanceof JavaField field
                            ? field.getType().getAllInvolvedRawTypes()
                            : ((JavaMethod) member).getReturnType().getAllInvolvedRawTypes();
                    for (JavaClass target : involved) {
                        String targetModule = moduleOf(target);
                        if (targetModule != null && !targetModule.equals(module)) {
                            events.add(SimpleConditionEvent.violated(member, String.format(
                                    "%s has a JPA relation to %s in module '%s' (refer by ID instead)",
                                    member.getFullName(), target.getName(), targetModule)));
                        }
                    }
                }
            }
        };
    }

    /** The top-level package under com.tally, or null for classes outside Tally. */
    private static String moduleOf(JavaClass type) {
        String pkg = type.getPackageName();
        if (!pkg.startsWith(ROOT + ".")) {
            return null;
        }
        String rest = pkg.substring(ROOT.length() + 1);
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }
}
