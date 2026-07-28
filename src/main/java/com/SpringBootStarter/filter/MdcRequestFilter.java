package com.SpringBootStarter.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(1)
public class MdcRequestFilter implements Filter {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String USER_ID_HEADER    = "X-User-Id";

    /*
     * traceId is intentionally NOT set here since Module 8 (Distributed Tracing).
     * Micrometer Tracing auto-populates MDC with the real "traceId" and "spanId"
     * for every request once micrometer-tracing-bridge-otel is on the classpath —
     * no filter code needed for that.
     *
     * Before Module 8, this filter generated its OWN random "traceId" as a stopgap.
     * Keeping that manual generation now would silently collide with Micrometer
     * Tracing's MDC key of the same name — whichever writes last wins, so logs
     * could show a traceId that does NOT match the real trace in Tempo. That
     * defeats the entire point of trace-to-log correlation.
     *
     * requestId stays here because it serves a different purpose: a simple,
     * client-facing correlation ID (safe to hand to a citizen/support ticket)
     * that's independent of the tracing system's internal ID format.
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;

        String requestId = httpRequest.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }

        String userId = httpRequest.getHeader(USER_ID_HEADER);

        try {
            MDC.put("requestId", requestId);

            if (userId != null && !userId.isBlank()) {
                MDC.put("userId", userId);
            }

            chain.doFilter(request, response);

        } finally {
            // CRITICAL: always clear MDC after request completes.
            // Tomcat reuses threads from a pool — without this, the next request
            // on this thread inherits stale MDC values from the previous request.
            MDC.clear();
        }
    }
}
