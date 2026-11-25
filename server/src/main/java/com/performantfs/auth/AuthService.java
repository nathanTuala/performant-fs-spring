package com.performantfs.auth;

import com.performantfs.config.AppProperties;
import com.performantfs.http.ApiException;
import com.performantfs.http.ErrorCode;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Accounts: registration (with the user's root folder) and credential checks. */
@Service
public class AuthService {

    private final JdbcClient db;
    private final PasswordEncoder passwords;
    private final AppProperties props;
    /** Checked when an email is unknown, so both failure paths cost one password hash. */
    private final String dummyHash;

    AuthService(JdbcClient db, PasswordEncoder passwords, AppProperties props) {
        this.db = db;
        this.passwords = passwords;
        this.props = props;
        this.dummyHash = passwords.encode("not-a-real-password");
    }

    /** Creates the account and its root folder in one transaction. */
    @Transactional
    public AuthUser register(String rawEmail, String password) {
        String email = normalize(rawEmail);
        UUID id;
        try {
            id = db.sql("INSERT INTO users (email, password_hash, quota_bytes) VALUES (?, ?, ?) RETURNING id")
                    .params(email, passwords.encode(password), props.defaultQuota().toBytes())
                    .query(UUID.class)
                    .single();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.NAME_CONFLICT, "An account with this email already exists");
        }
        UUID rootId = db.sql("INSERT INTO nodes (owner_id, parent_id, is_folder, name) VALUES (?, NULL, true, 'My Files') RETURNING id")
                .param(id)
                .query(UUID.class)
                .single();
        db.sql("UPDATE users SET root_id = ? WHERE id = ?").params(rootId, id).update();
        return new AuthUser(id, email, rootId);
    }

    /** The user, if the email and password match. Unknown emails and wrong passwords look the same. */
    public Optional<AuthUser> authenticate(String rawEmail, String password) {
        record Row(UUID id, String email, UUID rootId, String passwordHash) {}
        Optional<Row> row = db.sql("SELECT id, email, root_id, password_hash FROM users WHERE lower(email) = ?")
                .param(normalize(rawEmail))
                .query((rs, n) -> new Row(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4)))
                .optional();
        boolean matches = passwords.matches(password, row.map(Row::passwordHash).orElse(dummyHash));
        return row.filter(r -> matches).map(r -> new AuthUser(r.id(), r.email(), r.rootId()));
    }

    /** The account with this email, if any. */
    public Optional<AuthUser> find(String rawEmail) {
        return db.sql("SELECT id, email, root_id FROM users WHERE lower(email) = ?")
                .param(normalize(rawEmail))
                .query((rs, n) -> new AuthUser(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class)))
                .optional();
    }

    /** Replaces an account's password (hashed like any other). */
    public void setPassword(UUID userId, String password) {
        db.sql("UPDATE users SET password_hash = ? WHERE id = ?").params(passwords.encode(password), userId).update();
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
