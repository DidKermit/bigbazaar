package com.bigbazaar.gateway;

import com.bigbazaar.common.web.ApiError;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Edge authentication: rejects missing or invalid tokens before they reach a service. Fine-grained
 * authorization (roles, ownership) stays in each service, which re-validates the token.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, JsonMapper jsonMapper) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(auth -> auth
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/v1/auth/**").permitAll()
                        .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .pathMatchers("/api/**").authenticated()
                        .anyExchange().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> { })
                        .authenticationEntryPoint((exchange, ex) ->
                                write(exchange, jsonMapper, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                                        "Authentication is required.")))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((exchange, e) ->
                                write(exchange, jsonMapper, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                                        "Authentication is required."))
                        .accessDeniedHandler((exchange, e) ->
                                write(exchange, jsonMapper, HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                                        "You do not have access to this resource.")))
                .build();
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(@Value("${bigbazaar.jwt.secret}") String secret,
                                  @Value("${bigbazaar.jwt.issuer}") String issuer) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("bigbazaar.jwt.secret must be at least 32 bytes (set JWT_SECRET)");
        }
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    private static Mono<Void> write(ServerWebExchange exchange, JsonMapper jsonMapper, HttpStatus status,
                                    String code, String message) {
        String correlationId = exchange.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        byte[] body = jsonMapper.writeValueAsBytes(ApiError.of(code, message, correlationId));
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}
