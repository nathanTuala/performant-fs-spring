package com.performantfs.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.unit.DataSize;

class AppPropertiesTest {

    @EnableConfigurationProperties(AppProperties.class)
    static class Config {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void everySettingHasADefault() {
        runner.run(context -> {
            AppProperties props = context.getBean(AppProperties.class);
            assertThat(props.dataDir()).isEqualTo(Path.of(".data"));
            assertThat(props.chunkSize()).isEqualTo(DataSize.ofMegabytes(8));
            assertThat(props.defaultQuota()).isEqualTo(DataSize.ofGigabytes(2));
            assertThat(props.uploadTtl()).isEqualTo(Duration.ofHours(24));
            assertThat(props.secureCookies()).isFalse();
        });
    }

    @Test
    void settingsCanBeOverridden() {
        runner.withPropertyValues("pfs.chunk-size=1MB", "pfs.data-dir=/srv/blobs", "pfs.secure-cookies=true")
                .run(context -> {
                    AppProperties props = context.getBean(AppProperties.class);
                    assertThat(props.chunkSize()).isEqualTo(DataSize.ofMegabytes(1));
                    assertThat(props.dataDir()).isEqualTo(Path.of("/srv/blobs"));
                    assertThat(props.secureCookies()).isTrue();
                });
    }
}
