package com.bigbazaar.identity.web;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

final class CurrentUser {

    static UUID id(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private CurrentUser() {
    }
}
