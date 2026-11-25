package com.performantfs.support;

import java.time.Duration;
import java.util.function.Supplier;

/** Waits for background work: polls until the value satisfies the check, or fails after a timeout. */
public final class Eventually {

    private Eventually() {}

    public static <T> T eventually(Supplier<T> value, java.util.function.Predicate<T> done) {
        return eventually(Duration.ofSeconds(15), value, done);
    }

    public static <T> T eventually(Duration timeout, Supplier<T> value, java.util.function.Predicate<T> done) {
        long deadline = System.nanoTime() + timeout.toNanos();
        T last = value.get();
        while (!done.test(last)) {
            if (System.nanoTime() > deadline) throw new AssertionError("still not done after " + timeout + "; last value: " + last);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted", e);
            }
            last = value.get();
        }
        return last;
    }
}
