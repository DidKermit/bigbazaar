package com.bigbazaar.gateway;

import com.bigbazaar.common.web.ApiError;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
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
                        // Signed storage links (local dev storage): the URL signature is the authorization.
                        .pathMatchers(HttpMethod.GET, "/api/v1/storage/objects/**").permitAll()
                        .pathMatchers(HttpMethod.PUT, "/api/v1/storage/objects/**").permitAll()
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

    /**
     * Verifies RS256 tokens with identity's published public keys. Keys are fetched on first use and
     * cached; an unknown key ID triggers a refetch, so identity can rotate keys without a gateway restart.
     */
    @Bean
    ReactiveJwtDecoder jwtDecoder(@Value("${bigbazaar.jwt.jwk-set-uri}") String jwkSetUri,
                                  @Value("${bigbazaar.jwt.issuer}") String issuer) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
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
