package com.bigbazaar.identity.web;

import com.bigbazaar.common.web.Headers;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Reuses the gateway's correlation ID (or creates one) and puts it in logs and the response. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = sanitize(request.getHeader(Headers.CORRELATION_ID));
        MDC.put(Headers.MDC_CORRELATION_ID, correlationId);
        response.setHeader(Headers.CORRELATION_ID, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(Headers.MDC_CORRELATION_ID);
        }
    }

    private static String sanitize(String header) {
        if (header != null) {
            try {
                return UUID.fromString(header).toString();
            } catch (IllegalArgumentException ignored) {
                // Untrusted value; replace rather than log it.
            }
        }
        return UUID.randomUUID().toString();
    }
}
