package com.bigbazaar.identity.config;

import com.bigbazaar.common.web.ApiError;
import com.bigbazaar.identity.auth.AuthMethods;
import com.bigbazaar.identity.auth.JwtProperties;
import com.bigbazaar.identity.auth.SigningKeys;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import com.bigbazaar.identity.common.CorrelationIds;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    public static final String MFA_AUTHORITY = "AMR_MFA";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper jsonMapper) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/.well-known/jwks.json").permitAll()
                        // Local dev storage links: the HMAC signature in the URL is the authorization.
                        .requestMatchers("/api/v1/storage/objects/**").permitAll()
                        // Admin actions need a token from a login that passed MFA.
                        .requestMatchers("/api/v1/admin/**").access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasRole("ADMIN"),
                                AuthorityAuthorizationManager.hasAuthority(MFA_AUTHORITY)))
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(response, jsonMapper, HttpServletResponse.SC_UNAUTHORIZED,
                                        "UNAUTHENTICATED", "Authentication is required.")))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) ->
                                writeError(response, jsonMapper, HttpServletResponse.SC_UNAUTHORIZED,
                                        "UNAUTHENTICATED", "Authentication is required."))
                        .accessDeniedHandler((request, response, e) -> {
                            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                            boolean isAdmin = auth != null && auth.getAuthorities().stream()
                                    .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
                            if (isAdmin && lacksMfa(auth)) {
                                writeError(response, jsonMapper, HttpServletResponse.SC_FORBIDDEN, "MFA_REQUIRED",
                                        "Admin actions require a login with multi-factor authentication. "
                                                + "Enroll an authenticator app, then log in again.");
                            } else {
                                writeError(response, jsonMapper, HttpServletResponse.SC_FORBIDDEN,
                                        "ACCESS_DENIED", "You do not have access to this resource.");
                            }
                        }));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // BCrypt is adaptive (FR-AUTH-02); strength 12 is ~250ms on a typical core.
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    JwtEncoder jwtEncoder(SigningKeys signingKeys) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKeys.signingKey())));
    }

    @Bean
    JwtDecoder jwtDecoder(SigningKeys signingKeys, JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(signingKeys.publicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    /**
     * Maps {@code roles} to ROLE_* authorities and adds {@value #MFA_AUTHORITY} when the token's
     * {@code amr} claim shows a second factor was used at login.
     */
    static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>(roles.convert(jwt));
            List<String> amr = jwt.getClaimAsStringList("amr");
            if (amr != null && amr.contains(AuthMethods.MFA)) {
                authorities.add(new SimpleGrantedAuthority(MFA_AUTHORITY));
            }
            return authorities;
        });
        return converter;
    }

    private static boolean lacksMfa(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .noneMatch(a -> MFA_AUTHORITY.equals(a.getAuthority()));
    }

    private static void writeError(HttpServletResponse response, JsonMapper jsonMapper, int status, String code,
                                   String message) throws IOException {
        UUID correlationId = CorrelationIds.current();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(),
                ApiError.of(code, message, correlationId == null ? null : correlationId.toString()));
    }
}
