package com.tally.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

/**
 * F9 (Design Record §10 C, CLAUDE.md rule 14): inside {@code offers}, {@code subscription},
 * {@code invoicing} and {@code payment}, a domain class imports no Spring, no servlet API and no adapter.
 * {@code jakarta.persistence} annotations are allowed. Repositories are plain ports in the domain,
 * implemented in {@code adapter.persistence}.
 */
class F9PlainDomainTest {

    static final java.util.List<String> MODULES = InsideLayout.PORTS_AND_ADAPTERS;

    @ParameterizedTest
    @FieldSource("MODULES")
    void aDomainClassImportsNoSpringAndNoAdapter(String module) {
        noClasses().that().resideInAPackage("com.tally." + module + ".domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.servlet..", "com.tally..adapter..")
                .because("the domain is plain Java plus jakarta.persistence (F9)")
                .check(InsideLayout.CLASSES);
    }
}
