package com.tallyaccess;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Access, the second deployable (ADR-001; Slice 2 PR-6): answers "can I watch?" from its own copy of the access
 * facts, which it reads from the event log, also while billing is down (D13). Plain layers (Design Record §10
 * amendment): {@code web} → {@code application} → {@code persistence}, and {@code log}, which reads the event log
 * into {@code application}. It imports no billing code (F7, Access side).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AccessApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccessApplication.class, args);
    }
}
