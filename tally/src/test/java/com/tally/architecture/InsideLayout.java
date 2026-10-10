package com.tally.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;

/** Shared by F8, F9 and F10: the production classes and the modules each rule covers (Design Record §10 C). */
final class InsideLayout {

    /** Ports and adapters inside: domain, application, adapter (CLAUDE.md rule 14). */
    static final List<String> PORTS_AND_ADAPTERS = List.of("offers", "subscription", "invoicing", "payment");

    static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages("com.tally");

    private InsideLayout() {
    }
}
