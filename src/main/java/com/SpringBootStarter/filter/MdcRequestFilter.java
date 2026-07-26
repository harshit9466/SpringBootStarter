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

    private static final String TRACE_ID_HEADER  = "X-Trace-Id";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String USER_ID_HEADER    = "X-User-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;

        // If upstream (API Gateway / caller) already set a traceId, propagate it.
        // Otherwise generate a new one. This enables cross-service correlation.
        String traceId = httpRequest.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        String requestId = httpRequest.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }

        String userId = httpRequest.getHeader(USER_ID_HEADER);

        try {
            MDC.put("traceId",   traceId);
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
