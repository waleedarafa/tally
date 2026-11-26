package com.tally.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThat;

import com.tally.platform.Caller;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * F16 (Slice 4; CLAUDE.md rule 16; review of #90): every handler of billing's API, under {@code /v1/}, takes a
 * {@link Caller} parameter. AT-26 covers a new endpoint's 401 at once, from the contract; this covers its 404 rule: an
 * endpoint that names an account, a subscription or a payment reference cannot check the caller without the caller,
 * and one that forgets it fails here, not in production. Stricter than the review asked: every {@code /v1/} handler,
 * whatever it names, so no list of exceptions can go stale.
 */
class F16EveryApiHandlerTakesACallerTest {

    /** A request-mapping method whose path, with its class's, is under {@code /v1/}. */
    static final DescribedPredicate<JavaMethod> API_HANDLERS = DescribedPredicate.describe("handlers under /v1/",
            method -> paths(method).anyMatch(path -> path.startsWith("/v1/")));

    static Stream<String> paths(JavaMethod method) {
        String[] prefixes = method.getOwner().tryGetAnnotationOfType(RequestMapping.class)
                .map(mapping -> mapping.value().length > 0 ? mapping.value() : mapping.path())
                .filter(values -> values.length > 0).orElse(new String[] {""});
        String[] own = ownPaths(method);
        return Arrays.stream(prefixes).flatMap(prefix -> Arrays.stream(own).map(path -> prefix + path));
    }

    static String[] ownPaths(JavaMethod method) {
        String[] none = null;
        String[] paths = method.tryGetAnnotationOfType(GetMapping.class).map(GetMapping::value)
                .or(() -> method.tryGetAnnotationOfType(PostMapping.class).map(PostMapping::value))
                .or(() -> method.tryGetAnnotationOfType(PutMapping.class).map(PutMapping::value))
                .or(() -> method.tryGetAnnotationOfType(PatchMapping.class).map(PatchMapping::value))
                .or(() -> method.tryGetAnnotationOfType(DeleteMapping.class).map(DeleteMapping::value))
                .or(() -> method.tryGetAnnotationOfType(RequestMapping.class).map(RequestMapping::value))
                .orElse(none);
        if (paths == null) {
            return new String[0];
        }
        return paths.length == 0 ? new String[] {""} : paths;
    }

    static final ArchCondition<JavaMethod> TAKE_A_CALLER = new ArchCondition<>("take a Caller parameter") {
        @Override
        public void check(JavaMethod method, ConditionEvents events) {
            boolean takes = method.getRawParameterTypes().stream().map(JavaClass::getName)
                    .anyMatch(Caller.class.getName()::equals);
            events.add(new SimpleConditionEvent(method, takes, method.getFullName()
                    + (takes ? " takes" : " does not take") + " a Caller"));
        }
    };

    @Test
    void everyApiHandlerTakesACaller() {
        methods().that(API_HANDLERS).should(TAKE_A_CALLER)
                .because("a request acts only for the caller's account (CLAUDE.md rule 16)")
                .check(InsideLayout.CLASSES);
    }

    /** The rule sees the handlers: it would pass on nothing otherwise. Six handlers of the household API plus the ten of the flags admin API (ADR-053). */
    @Test
    void theRuleSeesEveryApiHandler() {
        long handlers = InsideLayout.CLASSES.stream().flatMap(owner -> owner.getMethods().stream())
                .filter(API_HANDLERS::test).count();

        assertThat(handlers).as("the /v1/ handlers of docs/api/openapi.yaml").isEqualTo(16);
    }
}
