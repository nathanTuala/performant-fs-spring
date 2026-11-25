package com.performantfs.observability;

import com.performantfs.auth.SessionCookieFilter;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One access-log line per API request (method, path, status, time, user) and the request's trace
 * id in an {@code X-Trace-Id} response header, so any request seen in the browser can be looked
 * up in the tracing backend. Runs inside the HTTP observation (so the trace exists) and outside
 * Spring Security (so 401s are logged too).
 *
 * <p>Only the path is logged, never the query string: it can hold search text.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLogFilter extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "X-Trace-Id";
    private static final Logger log = LoggerFactory.getLogger("access");

    private final Tracer tracer;

    RequestLogFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || path.equals("/api/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        Span span = tracer.currentSpan();
        if (span != null) response.setHeader(TRACE_HEADER, span.context().traceId());
        try {
            chain.doFilter(request, response);
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            Object user = request.getAttribute(SessionCookieFilter.USER_ATTRIBUTE);
            int status = response.getStatus();
            String line = "{} {} {} {}ms user={}";
            Object[] args = {request.getMethod(), request.getRequestURI(), status, ms, user == null ? "-" : user};
            if (status >= 500) log.error(line, args);
            else if (status >= 400) log.warn(line, args);
            else log.info(line, args);
        }
    }
}
