package com.fakeprovider;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Stand-in for the payment provider, reached by Tally over HTTP (FR-020).
 * Its package sits outside {@code com.tally} so it is never mistaken for a Tally module.
 */
@SpringBootApplication
public class FakeProviderApplication {

    public static void main(String[] args) {
        SpringApplication.run(FakeProviderApplication.class, args);
    }

    /**
     * The fake's own wall clock (CLAUDE.md rule 4), never Tally's test clock. Charge timestamps stay on
     * {@code Instant.now()} on purpose (CONTRACT.md section 7).
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
