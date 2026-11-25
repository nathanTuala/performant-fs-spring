package com.performantfs.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.performantfs.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class WebAppIT extends IntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"/", "/trash", "/search?q=report", "/folder/0b7c4a52-1f0e-4d38-9a51-3c1d7c2a9e10"})
    void clientRoutesLoadTheAppShellUncached(String path) {
        var res = anonymous().get(path);
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.text()).contains("<title>Files</title>");
        assertThat(res.header("cache-control")).contains("no-cache");
    }

    @Test
    void fingerprintedAssetsAreCachedForever() {
        var res = anonymous().get("/assets/app-1a2b3c.js");
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.header("cache-control")).contains("max-age=31536000").contains("immutable");
    }

    @Test
    void apiPathsNeverFallBackToTheAppShell() {
        var anon = anonymous().get("/api/nope");
        assertThat(anon.status()).isEqualTo(401);
        assertThat(anon.errorCode()).isEqualTo("unauthorized");

        var signedIn = newUser().api().get("/api/nope/" + UUID.randomUUID());
        assertThat(signedIn.status()).isEqualTo(404);
        assertThat(signedIn.errorCode()).isEqualTo("not_found");
    }

    @Test
    void unknownStaticFilesAre404() {
        assertThat(anonymous().get("/assets/missing.js").status()).isEqualTo(404);
    }
}
