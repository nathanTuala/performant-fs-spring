package com.performantfs.auth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The one account everyone uses while sign-in is switched off ({@code pfs.sign-in-required=false}).
 * Created on first use with a random password nobody knows, so it can't be signed into either;
 * turning sign-in back on simply stops handing it out.
 */
@Component
public class LocalAccount {

    static final String EMAIL = "local@performant-fs.local";

    private final JdbcClient db;
    private final AuthService auth;
    private volatile AuthUser cached;

    LocalAccount(JdbcClient db, AuthService auth) {
        this.db = db;
        this.auth = auth;
    }

    public AuthUser get() {
        AuthUser user = cached;
        if (user != null) return user;
        synchronized (this) {
            if (cached == null) cached = find().orElseGet(this::create);
            return cached;
        }
    }

    private Optional<AuthUser> find() {
        return db.sql("SELECT id, email, root_id FROM users WHERE lower(email) = ?")
                .param(EMAIL)
                .query((rs, n) -> new AuthUser(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class)))
                .optional();
    }

    private AuthUser create() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        try {
            return auth.register(EMAIL, Base64.getEncoder().encodeToString(secret));
        } catch (RuntimeException e) {
            // Another instance created it first.
            return find().orElseThrow(() -> e);
        }
    }
}
