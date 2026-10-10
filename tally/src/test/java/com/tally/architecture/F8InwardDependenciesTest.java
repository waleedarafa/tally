package com.tally.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

/**
 * F8 (Design Record §10 C): inside {@code offers}, {@code subscription}, {@code invoicing} and
 * {@code payment}, code never depends outward. The domain does not depend on the application layer, and
 * neither depends on an adapter. Adapters depend inward on both.
 */
class F8InwardDependenciesTest {

    static final java.util.List<String> MODULES = InsideLayout.PORTS_AND_ADAPTERS;

    @ParameterizedTest
    @FieldSource("MODULES")
    void theDomainDependsOnNeitherTheApplicationNorAnAdapter(String module) {
        noClasses().that().resideInAPackage("com.tally." + module + ".domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.tally." + module + ".application..", "com.tally." + module + ".adapter..")
                .because("dependencies point inward: the domain knows no use case and no technology (F8)")
                .check(InsideLayout.CLASSES);
    }

    @ParameterizedTest
    @FieldSource("MODULES")
    void theApplicationDoesNotDependOnAnAdapter(String module) {
        noClasses().that().resideInAPackage("com.tally." + module + ".application..")
                .should().dependOnClassesThat().resideInAPackage("com.tally." + module + ".adapter..")
                .because("the application reaches technology only through ports in the domain (F8)")
                .check(InsideLayout.CLASSES);
    }
}
