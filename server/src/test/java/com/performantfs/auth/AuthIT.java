package com.performantfs.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.performantfs.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class AuthIT extends IntegrationTest {

    @Autowired JdbcClient db;

    private static Map<String, String> creds(String email) {
        return Map.of("email", email, "password", "correct horse battery");
    }

    @Test
    void registeringCreatesARootFolderAndSignsIn() {
        var client = anonymous();
        var res = client.post("/api/auth/register", creds("Alice-" + System.nanoTime() + "@Example.com"));
        assertThat(res.status()).isEqualTo(201);
        assertThat(res.json().path("email").asString()).startsWith("alice-").endsWith("@example.com");
        assertThat(res.json().path("rootId").asString()).isNotBlank();
        assertThat(res.header("set-cookie")).contains("sid=").contains("HttpOnly").containsIgnoringCase("SameSite=Lax");

        var me = client.get("/api/auth/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.json().path("id")).isEqualTo(res.json().path("id"));
        assertThat(me.json().path("email")).isEqualTo(res.json().path("email"));
        assertThat(me.json().path("rootId")).isEqualTo(res.json().path("rootId"));
        assertThat(me.json().path("signInRequired").asBoolean()).isTrue();
    }

    @Test
    void emailsAreUniqueCaseInsensitively() {
        String email = "bob-" + System.nanoTime() + "@example.com";
        anonymous().post("/api/auth/register", creds(email));
        var again = anonymous().post("/api/auth/register", creds(email.toUpperCase()));
        assertThat(again.status()).isEqualTo(409);
    }

    @Test
    void aWrongPasswordAndAnUnknownEmailGetTheSameAnswer() {
        String email = "carol-" + System.nanoTime() + "@example.com";
        anonymous().post("/api/auth/register", creds(email));
        assertThat(anonymous().post("/api/auth/login", creds(email)).status()).isEqualTo(200);

        var wrong = anonymous().post("/api/auth/login", Map.of("email", email, "password", "nope-nope-nope"));
        var unknown = anonymous().post("/api/auth/login", creds("nobody-" + System.nanoTime() + "@example.com"));
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(unknown.status()).isEqualTo(401);
        assertThat(wrong.json()).isEqualTo(unknown.json());
    }

    @Test
    void logoutEndsTheSessionForGood() {
        var user = newUser();
        String cookie = user.api().cookie();
        assertThat(user.api().post("/api/auth/logout", null).status()).isEqualTo(204);
        user.api().cookie(cookie); // replaying the old cookie must not work
        assertThat(user.api().get("/api/auth/me").status()).isEqualTo(401);
    }

    @Test
    void inputIsValidated() {
        var res = anonymous().post("/api/auth/register", Map.of("email", "not-an-email", "password", "short"));
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.errorCode()).isEqualTo("bad_request");
    }

    @Test
    void passwordsAreStoredAsUpgradeableBcryptHashes() {
        var user = newUser();
        String hash = db.sql("SELECT password_hash FROM users WHERE id = ?").param(user.id()).query(String.class).single();
        // The {bcrypt} prefix lets the encoder move to a stronger algorithm later.
        assertThat(hash).startsWith("{bcrypt}$2").doesNotContain("correct horse battery");
    }
}
