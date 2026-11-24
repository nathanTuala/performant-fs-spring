package com.performantfs.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Server-side sessions. The cookie carries a random 256-bit token; the database keeps only its
 * SHA-256, so a leaked sessions table cannot be replayed as cookies.
 */
@Component
public class SessionStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient db;

    SessionStore(JdbcClient db) {
        this.db = db;
    }

    /** Creates a session and returns the raw token for the cookie. */
    public String create(UUID userId, Duration ttl) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        db.sql("INSERT INTO sessions (token_hash, user_id, expires_at) VALUES (?, ?, now() + make_interval(secs => ?))")
                .params(digest(token), userId, ttl.toSeconds())
                .update();
        return token;
    }

    public Optional<AuthUser> find(String token) {
        return db.sql("""
                        SELECT u.id, u.email, u.root_id FROM sessions s JOIN users u ON u.id = s.user_id
                        WHERE s.token_hash = ? AND s.expires_at > now()""")
                .param(digest(token))
                .query((rs, n) -> new AuthUser(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class)))
                .optional();
    }

    public void delete(String token) {
        db.sql("DELETE FROM sessions WHERE token_hash = ?").param(digest(token)).update();
    }

    private static byte[] digest(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JVM ships SHA-256
        }
    }
}
