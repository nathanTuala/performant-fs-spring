package com.performantfs.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.function.Function;

/**
 * Runs work inside a Micrometer observation, which becomes both a span in the trace and a timer
 * metric. The work gets the observation so it can attach what it found (result counts, ids) once
 * it knows them: low-cardinality values become metric tags, high-cardinality ones span
 * attributes only.
 */
public final class Observed {

    private Observed() {}

    public static <T> T observe(ObservationRegistry registry, String name, Function<Observation, T> work) {
        Observation observation = Observation.createNotStarted(name, registry).start();
        try (Observation.Scope scope = observation.openScope()) {
            return work.apply(observation);
        } catch (RuntimeException e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }
}
