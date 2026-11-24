package com.performantfs.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.performantfs.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class SessionStoreIT extends IntegrationTest {

    @Autowired SessionStore sessions;
    @Autowired JdbcClient db;

    private UUID userId;

    @BeforeEach
    void user() {
        userId = db.sql("INSERT INTO users (email, password_hash) VALUES (?, 'x') RETURNING id")
                .param("s" + UUID.randomUUID() + "@test.dev")
                .query(UUID.class)
                .single();
    }

    @Test
    void aTokenFindsItsUserUntilDeleted() {
        String token = sessions.create(userId, Duration.ofDays(1));
        assertThat(sessions.find(token)).hasValueSatisfying(u -> assertThat(u.id()).isEqualTo(userId));
        sessions.delete(token);
        assertThat(sessions.find(token)).isEmpty();
    }

    @Test
    void onlyTheTokenHashIsStored() throws Exception {
        String token = sessions.create(userId, Duration.ofDays(1));
        byte[] stored = db.sql("SELECT token_hash FROM sessions WHERE user_id = ?").param(userId).query(byte[].class).single();
        byte[] expected = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        assertThat(stored).isEqualTo(expected);
        assertThat(token).hasSize(43); // 32 random bytes, base64url without padding
    }

    @Test
    void expiredSessionsAreIgnored() {
        String token = sessions.create(userId, Duration.ofSeconds(-1));
        assertThat(sessions.find(token)).isEmpty();
    }

    @Test
    void unknownTokensFindNothing() {
        assertThat(sessions.find("not-a-real-token")).isEmpty();
    }
}
