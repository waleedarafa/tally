package com.tally.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tally.account.AccountStanding;
import org.junit.jupiter.api.Test;

/**
 * F3 (design.md §3): {@code account} exposes {@code AccountStanding.set} to {@code collections} only. No
 * class outside those two modules may depend on {@link AccountStanding} at all: not call it, not hold it,
 * not implement it. Slice 1R adds the rule before anything calls it.
 */
class F3StandingTest {

    @Test
    void onlyCollectionsSetsAnAccountsStanding() {
        noClasses().that().resideOutsideOfPackages("com.tally.account..", "com.tally.collections..")
                .should().dependOnClassesThat().areAssignableTo(AccountStanding.class)
                .because("only Collections changes an account's standing (F3, design.md §3)")
                .check(InsideLayout.CLASSES);
    }
}
