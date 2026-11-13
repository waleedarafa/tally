package com.tally.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tally.shared.DomainEvent;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * F12 (Slice 1R PR-10; design.md §3; ADR-033): {@code access} and {@code accessfeed} may use {@code subscription} and
 * {@code account} only through their events. Neither ever calls a billing API: each reacts to events and keeps its
 * own tables. The module graph allows the edge; this test narrows it to the events and the types they carry (an
 * event's enum, for one). {@code access}'s edge is temporary, until it leaves in Slice 2 PR-9; {@code accessfeed}'s is
 * permanent.
 */
class F12AccessReactsOnlyToEventsTest {

    /** The modules Access may use, through their events only, until Slice 2. */
    static boolean upstream(JavaClass type) {
        return type.getPackageName().startsWith("com.tally.subscription")
                || type.getPackageName().startsWith("com.tally.account");
    }

    /** Every event of the upstream modules, and every upstream type one of their fields carries. */
    static Set<JavaClass> events() {
        Set<JavaClass> events = InsideLayout.CLASSES.stream()
                .filter(c -> upstream(c) && c.isAssignableTo(DomainEvent.class) && !c.isInterface())
                .collect(Collectors.toSet());
        Set<JavaClass> carried = events.stream()
                .flatMap(event -> event.getFields().stream())
                .map(JavaField::getRawType)
                .filter(F12AccessReactsOnlyToEventsTest::upstream)
                .collect(Collectors.toSet());
        carried.addAll(events);
        return carried;
    }

    @Test
    void accessUsesOnlyTheEventsOfSubscriptionAndAccount() {
        reactsOnlyToEvents("com.tally.access..", "Access reacts to events and answers from its own tables; it never"
                + " calls a billing API (Slice 1R PR-10; the edge is temporary until Slice 2 PR-9)");
    }

    @Test
    void theAccessFeedUsesOnlyTheEventsOfSubscriptionAndAccount() {
        reactsOnlyToEvents("com.tally.accessfeed..", "the access feed builds the facts from events and keeps them in"
                + " its own tables; it never calls the API of subscription or account (ADR-033)");
    }

    static void reactsOnlyToEvents(String module, String reason) {
        Set<JavaClass> allowed = events();
        assertThat(allowed).extracting(JavaClass::getSimpleName).contains("SubscriptionChanged", "StandingChanged");

        noClasses().that().resideInAPackage(module)
                .should().dependOnClassesThat(DescribedPredicate.describe(
                        "are in subscription or account but are not their events or a type an event carries",
                        (JavaClass target) -> upstream(target) && !allowed.contains(target)))
                .because(reason)
                .check(InsideLayout.CLASSES);
    }
}
