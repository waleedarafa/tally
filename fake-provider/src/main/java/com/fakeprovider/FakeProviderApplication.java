package com.fakeprovider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Stand-in for the payment provider, reached by Tally over HTTP (ADR-020).
 * Its package sits outside {@code com.tally} so it is never mistaken for a Tally module.
 */
@SpringBootApplication
public class FakeProviderApplication {

    public static void main(String[] args) {
        SpringApplication.run(FakeProviderApplication.class, args);
    }
}
