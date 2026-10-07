package com.tally.platform.internal;

import com.tally.platform.InvariantCheck;
import com.tally.platform.PeriodicJob;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.stereotype.Component;

/**
 * Runs every {@link InvariantCheck} as a periodic job (daily by default, ADR-024) and reports what the
 * latest run found as {@code tally.invariant.violations{check}}.
 */
@Component
@EnableConfigurationProperties(InvariantJobs.Properties.class)
class InvariantJobs {

    /** @param interval how often each invariant is checked ({@code tally.invariants.interval}) */
    @ConfigurationProperties("tally.invariants")
    record Properties(@DefaultValue("1d") Duration interval) {
    }

    private final List<PeriodicJob> jobs;

    InvariantJobs(ObjectProvider<InvariantCheck> checks, Properties properties, MeterRegistry meters) {
        this.jobs = checks.orderedStream().map(check -> job(check, properties.interval(), meters)).toList();
    }

    List<PeriodicJob> jobs() {
        return jobs;
    }

    private static PeriodicJob job(InvariantCheck check, Duration interval, MeterRegistry meters) {
        AtomicLong found = new AtomicLong();
        Gauge.builder("tally.invariant.violations", found, AtomicLong::get)
                .tag("check", check.name()).strongReference(true).register(meters);
        return new PeriodicJob() {
            @Override
            public String name() {
                return "invariant." + check.name();
            }

            @Override
            public String table() {
                return check.table();
            }

            @Override
            public Duration interval() {
                return interval;
            }

            @Override
            public void run() {
                found.set(check.violations());
            }

            @Override
            public long waitingWork() {
                return found.get();
            }
        };
    }
}
