package com.performantfs.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.performantfs.support.IntegrationTest;
import org.junit.jupiter.api.Test;

class HealthIT extends IntegrationTest {

    @Test
    void reportsHealthyOnceTheDatabaseAnswers() {
        var res = anonymous().get("/api/health");
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.json().path("ok").asBoolean()).isTrue();
    }

    @Test
    void protectedEndpointsAnswer401InTheStandardErrorShape() {
        var res = anonymous().get("/api/nodes/anything");
        assertThat(res.status()).isEqualTo(401);
        assertThat(res.errorCode()).isEqualTo("unauthorized");
        assertThat(res.json().path("error").path("message").asString()).isEqualTo("Sign in to continue");
    }
}
