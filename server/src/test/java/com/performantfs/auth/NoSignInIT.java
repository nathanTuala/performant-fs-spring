package com.performantfs.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.performantfs.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** With sign-in switched off, everyone without a session is the one local account. */
@TestPropertySource(properties = "pfs.sign-in-required=false")
class NoSignInIT extends IntegrationTest {

    @Test
    void aRequestWithoutASessionActsAsTheLocalAccount() {
        var me = anonymous().get("/api/auth/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.json().path("email").asString()).isEqualTo(LocalAccount.EMAIL);
        assertThat(me.json().path("signInRequired").asBoolean()).isFalse();
    }

    @Test
    void everyoneWithoutASessionSharesTheSameFiles() {
        var first = anonymous();
        String root = first.get("/api/auth/me").json().path("rootId").asString();
        var created = first.post("/api/folders", Map.of("parentId", root, "name", "shared " + System.nanoTime()));
        assertThat(created.status()).isEqualTo(201);

        var second = anonymous();
        assertThat(second.get("/api/auth/me").json().path("rootId").asString()).isEqualTo(root);
        assertThat(second.get("/api/nodes/" + root + "/children").text())
                .contains(created.json().path("name").asString());
    }

    @Test
    void aSignedInSessionStillWins() {
        var alice = newUser();
        var me = alice.api().get("/api/auth/me").json();
        assertThat(me.path("email").asString()).isNotEqualTo(LocalAccount.EMAIL);
        assertThat(me.path("rootId").asString()).isEqualTo(alice.rootId().toString());
    }
}
