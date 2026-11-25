package com.performantfs.observability;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

@Configuration(proxyBeanMethods = false)
class ObservabilityConfig {

    /**
     * Health checks arrive every few seconds from Docker and load balancers; tracing them would
     * bury the requests people actually make.
     */
    @Bean
    ObservationPredicate skipHealthChecks() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext request
                && (request.getCarrier().getRequestURI().equals("/api/health")
                    || request.getCarrier().getRequestURI().startsWith("/actuator")));
    }
}
