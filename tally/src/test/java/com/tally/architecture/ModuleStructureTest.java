package com.tally.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.TallyApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Spring Modulith verification (ADR-001, ADR-003, ADR-014, ADR-027): no cycles, no access to
 * another module's internal packages, and only the dependencies each module declares in its
 * {@code package-info.java}. This is also what keeps {@code platform} free of business modules.
 */
class ModuleStructureTest {

    static final ApplicationModules MODULES = ApplicationModules.of(TallyApplication.class);

    @Test
    void modulesRespectTheirDeclaredBoundaries() {
        MODULES.verify();
    }

    /** CLAUDE.md rule 1: one top-level package per bounded context, plus shared, platform and checkout (R5). */
    @Test
    void topLevelPackagesAreExactlyTheAgreedModules() {
        assertThat(MODULES.stream().map(ApplicationModule::getIdentifier).map(Object::toString))
                .containsExactlyInAnyOrder(
                        "shared", "platform", "account", "catalog",
                        "subscription", "invoicing", "payment", "entitlement", "checkout");
    }
}
