package com.performantfs.auth;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.performantfs.config.AppProperties;
import com.performantfs.http.ApiException;
import com.performantfs.http.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    /** The current user, and whether the app asks people to sign in at all. */
    record Me(@JsonUnwrapped AuthUser user, boolean signInRequired) {}

    record Credentials(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 256) String password) {}

    private final AuthService auth;
    private final SessionStore sessions;
    private final AppProperties props;

    AuthController(AuthService auth, SessionStore sessions, AppProperties props) {
        this.auth = auth;
        this.sessions = sessions;
        this.props = props;
    }

    @PostMapping("/register")
    ResponseEntity<AuthUser> register(@Valid @RequestBody Credentials body) {
        AuthUser user = auth.register(body.email(), body.password());
        return signedIn(HttpStatus.CREATED, user);
    }

    @PostMapping("/login")
    ResponseEntity<AuthUser> login(@Valid @RequestBody Credentials body) {
        AuthUser user = auth.authenticate(body.email(), body.password())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "Email or password is incorrect"));
        return signedIn(HttpStatus.OK, user);
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        SessionCookieFilter.tokenFrom(request).ifPresent(sessions::delete);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", 0).toString()).build();
    }

    @GetMapping("/me")
    Me me(@AuthenticationPrincipal AuthUser user) {
        if (user == null) throw new ApiException(ErrorCode.UNAUTHORIZED, "Sign in to continue");
        return new Me(user, props.signInRequired());
    }

    private ResponseEntity<AuthUser> signedIn(HttpStatus status, AuthUser user) {
        String token = sessions.create(user.id(), props.sessionTtl());
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookie(token, props.sessionTtl().toSeconds()).toString())
                .body(user);
    }

    /** HttpOnly (no script access), SameSite=Lax (no cross-site POSTs), Secure behind HTTPS. */
    private ResponseCookie cookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(SessionCookieFilter.COOKIE, value)
                .httpOnly(true)
                .sameSite("Lax")
                .secure(props.secureCookies())
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
    }
}
