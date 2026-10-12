package com.bigbazaar.gateway;

import com.bigbazaar.common.web.Headers;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Runs before security so even rejected requests carry a correlation ID. A valid incoming UUID is
 * kept so clients can trace their own requests; anything else is replaced.
 */
@Component
public class CorrelationIdFilter implements WebFilter, Ordered {

    public static final String ATTRIBUTE = CorrelationIdFilter.class.getName() + ".id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = sanitize(exchange.getRequest().getHeaders().getFirst(Headers.CORRELATION_ID));
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> h.set(Headers.CORRELATION_ID, correlationId))
                .build();
        exchange.getResponse().getHeaders().set(Headers.CORRELATION_ID, correlationId);
        ServerWebExchange mutated = exchange.mutate().request(request).build();
        mutated.getAttributes().put(ATTRIBUTE, correlationId);
        return chain.filter(mutated);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    static String sanitize(String header) {
        if (header != null) {
            try {
                return UUID.fromString(header).toString();
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        return UUID.randomUUID().toString();
    }
}
