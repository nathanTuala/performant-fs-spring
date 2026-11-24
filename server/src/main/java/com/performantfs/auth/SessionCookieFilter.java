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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns a valid session cookie into an authenticated {@link AuthUser} for this request. Requests
 * without one carry on anonymously; Spring Security then answers 401 wherever a user is required.
 */
public class SessionCookieFilter extends OncePerRequestFilter {

    public static final String COOKIE = "sid";

    private final SessionStore sessions;

    public SessionCookieFilter(SessionStore sessions) {
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        tokenFrom(request)
                .flatMap(sessions::find)
                .ifPresent(user -> SecurityContextHolder.getContext().setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(user, null, List.of())));
        chain.doFilter(request, response);
    }

    static Optional<String> tokenFrom(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();
        return Arrays.stream(cookies).filter(c -> COOKIE.equals(c.getName())).map(Cookie::getValue).findFirst();
    }
}
