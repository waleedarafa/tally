package com.tally.platform.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The platform owns the threads that run asynchronous event listeners (FR-027; slice 1 report
 * D13 and R3). Spring Modulith's {@code @ApplicationModuleListener} is {@code @Async}; the
 * {@link AsyncConfigurer} below hands every such listener to {@code eventListenerExecutor}. That
 * pool is the application's only {@code Executor} bean, so Spring Boot does not create its own.
 *
 * <p>Health (FR-023), per instance:
 * <ul>
 *   <li>{@code tally.events.listeners.active}: listeners running now</li>
 *   <li>{@code tally.events.listeners.queued}: listeners waiting for a thread (waiting work)</li>
 *   <li>{@code tally.events.listeners.threads}: threads in the pool</li>
 *   <li>{@code tally.events.listeners.completed}: listener runs finished, successful or not</li>
 *   <li>{@code tally.events.listeners.failed}: listener runs that threw; the publication stays
 *       incomplete and is resubmitted (FR-022)</li>
 *   <li>{@code tally.events.listeners.last_success_age_seconds}: since a listener last completed
 *       without throwing, or since start-up if none has</li>
 * </ul>
 * The registry-wide view stays in {@code tally.events.incomplete} and
 * {@code tally.events.oldest_incomplete_age_seconds} ({@link EventHealth}).
 */
@Configuration(proxyBeanMethods = false)
class EventListenerExecution {

    private static final Logger log = LoggerFactory.getLogger(EventListenerExecution.class);

    /**
     * Set by the uncaught-exception handler, which runs on the listener's own thread after the
     * listener threw; the task itself then returns normally.
     */
    private static final ThreadLocal<Boolean> FAILED = ThreadLocal.withInitial(() -> false);

    @Bean
    ThreadPoolTaskExecutor eventListenerExecutor(EventProperties properties, MeterRegistry meters, Clock clock) {
        AtomicReference<Instant> lastSuccess = new AtomicReference<>(clock.instant());

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("tally-events-");
        executor.setCorePoolSize(properties.listenerThreads());
        executor.setMaxPoolSize(properties.listenerThreads());
        // Unbounded: a rejected listener would leave its publication for resubmission minutes later.
        executor.setQueueCapacity(Integer.MAX_VALUE);
        // As Spring Modulith defaults Boot's executor: give running listeners 2 s to finish on shutdown.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationMillis(Duration.ofSeconds(2).toMillis());
        executor.setTaskDecorator(task -> () -> {
            FAILED.set(false);
            try {
                task.run();
                if (!FAILED.get()) {
                    lastSuccess.set(clock.instant());
                }
            } finally {
                FAILED.remove();
            }
        });

        Gauge.builder("tally.events.listeners.active", executor, ThreadPoolTaskExecutor::getActiveCount)
                .strongReference(true).register(meters);
        Gauge.builder("tally.events.listeners.queued", executor, ThreadPoolTaskExecutor::getQueueSize)
                .strongReference(true).register(meters);
        Gauge.builder("tally.events.listeners.threads", executor, ThreadPoolTaskExecutor::getPoolSize)
                .strongReference(true).register(meters);
        FunctionCounter.builder("tally.events.listeners.completed", executor,
                e -> e.getThreadPoolExecutor().getCompletedTaskCount()).register(meters);
        Gauge.builder("tally.events.listeners.last_success_age_seconds", lastSuccess,
                        last -> Duration.between(last.get(), clock.instant()).toMillis() / 1000.0)
                .baseUnit("seconds").strongReference(true).register(meters);
        return executor;
    }

    @Bean
    AsyncConfigurer eventListenerAsyncConfigurer(ThreadPoolTaskExecutor eventListenerExecutor, MeterRegistry meters) {
        Counter failed = Counter.builder("tally.events.listeners.failed").register(meters);
        return new AsyncConfigurer() {

            @Override
            public Executor getAsyncExecutor() {
                return eventListenerExecutor;
            }

            @Override
            public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
                return (Throwable error, Method method, Object... params) -> {
                    FAILED.set(true);
                    failed.increment();
                    log.warn("Event listener {}.{} failed; its publication stays incomplete for resubmission"
                            + " (FR-022)", method.getDeclaringClass().getSimpleName(), method.getName(), error);
                };
            }
        };
    }
}
