package com.performantfs.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns a valid session cookie into an authenticated {@link AuthUser} for this request. Requests
 * without one carry on anonymously, and Spring Security answers 401 wherever a user is required,
 * unless sign-in is switched off: then they act as the {@code fallback} account.
 */
public class SessionCookieFilter extends OncePerRequestFilter {

    public static final String COOKIE = "sid";
    /** Request attribute holding the authenticated user's id. */
    public static final String USER_ATTRIBUTE = "pfs.userId";

    private final SessionStore sessions;
    private final Supplier<AuthUser> fallback;

    /** @param fallback who a request without a session is, or null to leave it anonymous */
    public SessionCookieFilter(SessionStore sessions, Supplier<AuthUser> fallback) {
        this.sessions = sessions;
        this.fallback = fallback;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        tokenFrom(request)
                .flatMap(sessions::find)
                .or(() -> Optional.ofNullable(fallback).map(Supplier::get))
                .ifPresent(user -> {
                    SecurityContextHolder.getContext().setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
                    // For the access log, which runs outside Spring Security's context.
                    request.setAttribute(USER_ATTRIBUTE, user.id());
                });
        chain.doFilter(request, response);
    }

    static Optional<String> tokenFrom(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();
        return Arrays.stream(cookies).filter(c -> COOKIE.equals(c.getName())).map(Cookie::getValue).findFirst();
    }
}
