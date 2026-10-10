package com.tally.platform.internal;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** Every background job on this instance. Health metrics are registered from it (FR-023). */
@Component
class JobRegistry {

    private final Map<String, RegisteredJob> jobs = new ConcurrentHashMap<>();
    private final List<Consumer<RegisteredJob>> listeners = new CopyOnWriteArrayList<>();

    /** Registers a job, or keeps the existing one when a stopped job is started again. */
    RegisteredJob register(RegisteredJob job) {
        RegisteredJob existing = jobs.putIfAbsent(job.name(), job);
        if (existing != null) {
            return existing;
        }
        listeners.forEach(l -> l.accept(job));
        return job;
    }

    /** Calls {@code listener} for every job, now and when registered later. */
    void onRegistered(Consumer<RegisteredJob> listener) {
        listeners.add(listener);
        jobs.values().forEach(listener);
    }

    Optional<RegisteredJob> find(String name) {
        return Optional.ofNullable(jobs.get(name));
    }

    List<RegisteredJob> jobs() {
        return List.copyOf(jobs.values());
    }
}
