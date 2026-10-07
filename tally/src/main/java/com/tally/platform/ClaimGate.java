package com.tally.platform;

/**
 * Lets a queue pause claiming, for example while a circuit breaker is open: no item is claimed
 * while {@link #open()} is false. Items stay READY, so the queue's health shows the waiting work.
 */
@FunctionalInterface
public interface ClaimGate {

    ClaimGate ALWAYS_OPEN = () -> true;

    boolean open();
}
