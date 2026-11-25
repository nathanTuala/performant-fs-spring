package com.performantfs.observability;

import com.performantfs.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** One line at startup saying how this instance is configured, so a log shows what it ran with. */
@Component
class StartupSummary {

    private static final Logger log = LoggerFactory.getLogger(StartupSummary.class);

    private final AppProperties props;
    private final Environment env;

    StartupSummary(AppProperties props, Environment env) {
        this.props = props;
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    void ready() {
        log.info("ready: data={} model={} sign-in={} quota={} max-file={} chunk={} otlp-export={}",
                props.dataDir().toAbsolutePath(), props.embeddingModelDir().getFileName(),
                props.signInRequired() ? "required" : "off", props.defaultQuota(), props.maxFileSize(), props.chunkSize(),
                env.getProperty("management.tracing.export.enabled", "true"));
    }
}
