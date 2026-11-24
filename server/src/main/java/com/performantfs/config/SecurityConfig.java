package com.performantfs.config;

import com.performantfs.http.ErrorBody;
import com.performantfs.http.ErrorCode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

/**
 * Who may call what. Sessions are our own (an opaque cookie backed by the sessions table, added
 * with auth), so Spring's HTTP session, form login and basic auth are all off.
 *
 * <p>CSRF tokens are off: the session cookie is SameSite=Lax and every state-changing endpoint
 * takes JSON or a raw body, which a cross-site form cannot send with credentials.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, AuthenticationEntryPoint jsonUnauthorized) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .requestCache(c -> c.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health", "/api/auth/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll()) // the web app's static files
                .exceptionHandling(e -> e.authenticationEntryPoint(jsonUnauthorized))
                .build();
    }

    /** 401s use the same {error: {code, message}} shape as every other failure. */
    @Bean
    AuthenticationEntryPoint jsonUnauthorized(JsonMapper json) {
        return (request, response, ex) -> {
            response.setStatus(ErrorCode.UNAUTHORIZED.status().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getOutputStream(), ErrorBody.of(ErrorCode.UNAUTHORIZED, "Sign in to continue"));
        };
    }
}
