package com.tally.platform;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one place that reads the system clock (D10). Everything else injects {@link Clock},
 * so tests can move time. A fitness function forbids system-clock reads anywhere else.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
