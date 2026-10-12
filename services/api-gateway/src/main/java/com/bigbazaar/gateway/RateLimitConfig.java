package com.bigbazaar.gateway;

import java.net.InetSocketAddress;
import java.util.Optional;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimitConfig {

    /**
     * Limits by client IP. Behind a trusted load balancer the left-most X-Forwarded-For entry is the
     * client; configure the LB to overwrite (not append to) that header.
     */
    @Bean
    @Primary
    KeyResolver clientIpKeyResolver() {
        return exchange -> {
            String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return Mono.just(forwarded.split(",")[0].trim());
            }
            return Mono.just(Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                    .map(InetSocketAddress::getAddress)
                    .map(address -> address.getHostAddress())
                    .orElse("unknown"));
        };
    }

    /** Authenticated traffic is limited per user (JWT subject), falling back to IP. */
    @Bean
    KeyResolver userKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(principal -> "user:" + principal.getName())
                .switchIfEmpty(clientIpKeyResolver().resolve(exchange));
    }
}
