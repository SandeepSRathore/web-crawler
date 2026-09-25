package com.webcrawler;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Enforces a minimum gap between requests to the same host. Each caller books the host's
 * next free time slot and sleeps until it arrives, so different hosts never wait on each other.
 */
final class HostThrottle {

    private final ConcurrentHashMap<String, Long> nextFreeSlot = new ConcurrentHashMap<>();

    void acquire(String host, Duration delay) throws InterruptedException {
        long now = System.nanoTime();
        long gap = delay.toNanos();
        long[] mySlot = new long[1];
        nextFreeSlot.compute(host, (h, next) -> {
            long slot = (next == null || next - now < 0) ? now : next;
            mySlot[0] = slot;
            return slot + gap;
        });
        long wait = mySlot[0] - now;
        if (wait > 0) {
            TimeUnit.NANOSECONDS.sleep(wait);
        }
    }
}
