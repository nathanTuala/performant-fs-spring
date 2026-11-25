package com.performantfs.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

/**
 * Sends log lines to the OpenTelemetry log exporter as well as the console, each tagged with the
 * trace it belongs to, so a slow trace leads straight to its logs. Spring Boot configures the
 * exporter but leaves attaching it to Logback to the app; this does it in code rather than in a
 * logback.xml, so Boot's own console setup (including structured JSON) stays as it is.
 */
@Component
@ConditionalOnBooleanProperty("management.logging.export.enabled")
class LogExport implements SmartInitializingSingleton {

    private final OpenTelemetry openTelemetry;

    LogExport(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void afterSingletonsInstantiated() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if (root.getAppender("OTEL") == null) {
            var appender = new OpenTelemetryAppender();
            appender.setName("OTEL");
            appender.setContext(context);
            appender.start();
            root.addAppender(appender);
        }
        OpenTelemetryAppender.install(openTelemetry);
    }
}
