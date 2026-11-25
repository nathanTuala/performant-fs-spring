package com.performantfs.support;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Records every finished observation (span) in the app, for tests to inspect. */
@TestConfiguration(proxyBeanMethods = false)
public class RecordedObservations {

    public record Recorded(String name, Map<String, String> low, Map<String, String> high) {}

    public static final class Recorder implements ObservationHandler<Observation.Context> {
        private final List<Recorded> stopped = new CopyOnWriteArrayList<>();

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        @Override
        public void onStop(Observation.Context context) {
            stopped.add(new Recorded(context.getName(), map(context.getLowCardinalityKeyValues()), map(context.getHighCardinalityKeyValues())));
        }

        private static Map<String, String> map(Iterable<KeyValue> values) {
            return java.util.stream.StreamSupport.stream(values.spliterator(), false)
                    .collect(Collectors.toMap(KeyValue::getKey, KeyValue::getValue, (a, b) -> b));
        }

        public List<Recorded> named(String name) {
            return stopped.stream().filter(r -> r.name().equals(name)).toList();
        }

        public void clear() {
            stopped.clear();
        }
    }

    @Bean
    Recorder observationRecorder() {
        return new Recorder();
    }
}
