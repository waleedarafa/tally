package com.tally.architecture;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import org.junit.jupiter.api.Test;

/**
 * F10 (Design Record §10 C): {@code account} is built in closed layers, on its real package names:
 * {@code adapter.web} → {@code application} → {@code domain}. A controller never skips the service, and
 * a lower layer never calls a higher one. The module's public API in the base package is how the web
 * layer reaches the service. {@code collections} likewise since Slice 3 PR-5, when "pay now" gave it a web layer:
 * {@code adapter.web} → {@code application} → {@code domain}.
 */
class F10ClosedLayersTest {

    @Test
    void accountIsBuiltInClosedLayers() {
        layeredArchitecture().consideringOnlyDependenciesInLayers()
                .layer("Web").definedBy("com.tally.account.adapter.web..")
                .layer("Application").definedBy("com.tally.account.application..")
                .layer("Domain").definedBy("com.tally.account.domain..")
                .whereLayer("Web").mayNotBeAccessedByAnyLayer()
                .whereLayer("Application").mayOnlyBeAccessedByLayers("Web")
                .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application")
                .because("closed layers: never upward, never skipping a layer (F10)")
                .check(InsideLayout.CLASSES);
    }

    @Test
    void collectionsIsBuiltInClosedLayers() {
        layeredArchitecture().consideringOnlyDependenciesInLayers()
                .layer("Web").definedBy("com.tally.collections.adapter.web..")
                .layer("Application").definedBy("com.tally.collections.application..")
                .layer("Domain").definedBy("com.tally.collections.domain..")
                .whereLayer("Web").mayNotBeAccessedByAnyLayer()
                .whereLayer("Application").mayOnlyBeAccessedByLayers("Web")
                .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application")
                .because("closed layers: never upward, never skipping a layer (F10)")
                .check(InsideLayout.CLASSES);
    }
}
