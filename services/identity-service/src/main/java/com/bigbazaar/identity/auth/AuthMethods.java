package com.bigbazaar.identity.auth;

import java.util.List;

/** Values for the access token's {@code amr} claim (RFC 8176). */
public final class AuthMethods {
    public static final String PASSWORD = "pwd";
    public static final String MFA = "mfa";

    static List<String> of(boolean mfaVerified) {
        return mfaVerified ? List.of(PASSWORD, MFA) : List.of(PASSWORD);
    }

    private AuthMethods() {
    }
}
