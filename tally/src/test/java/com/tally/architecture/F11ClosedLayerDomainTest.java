package com.tally.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

/**
 * F11 (Design Record §10 C; Slice 1R close-out): inside the closed-layer modules {@code account} and
 * {@code collections}, the domain depends on no web technology and no adapter. Unlike F9, Spring Data is
 * allowed: a closed-layer domain may hold its repository. Together with F9 it replaces the first run's FF-8
 * ({@code domainDoesNotDependOnAdapters}).
 */
class F11ClosedLayerDomainTest {

    static final List<String> MODULES = List.of("account", "collections");

    @ParameterizedTest
    @FieldSource("MODULES")
    void aClosedLayerDomainUsesNoWebAndNoAdapter(String module) {
        noClasses().that().resideInAPackage("com.tally." + module + ".domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..",
                        "org.springframework.http..", "jakarta.servlet..", "com.tally..adapter..")
                .because("a closed-layer domain knows no web technology and no adapter; Spring Data is allowed (F11)")
                .check(InsideLayout.CLASSES);
    }

    /** No domain package escapes both rules: a new module's domain must join F9 or F11. */
    @Test
    void everyDomainPackageIsCoveredByF9OrF11() {
        Set<String> withADomain = InsideLayout.CLASSES.stream()
                .map(c -> c.getPackageName().split("\\."))
                .filter(parts -> parts.length > 3 && parts[3].equals("domain"))
                .map(parts -> parts[2])
                .collect(Collectors.toSet());

        assertThat(withADomain).contains("account", "payment");
        assertThat(Stream.concat(InsideLayout.PORTS_AND_ADAPTERS.stream(), MODULES.stream()).toList())
                .as("Modules with a domain package must be checked by F9 or F11")
                .containsAll(withADomain);
    }
}
