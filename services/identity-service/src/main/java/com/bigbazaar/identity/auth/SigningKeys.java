package com.bigbazaar.identity.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.stereotype.Component;

/**
 * Loads the RSA signing key and derives its public half, so only one file has to be provisioned.
 * The key ID is the RFC 7638 thumbprint: it changes automatically when the key is rotated.
 */
@Component
public class SigningKeys {

    private final RSAKey signingKey;

    public SigningKeys(JwtProperties properties) {
        RSAPrivateKey privateKey = readPrivateKey(properties.privateKeyLocation());
        RSAPublicKey publicKey = derivePublicKey(privateKey);
        try {
            RSAKey withoutId = new RSAKey.Builder(publicKey).privateKey(privateKey).build();
            this.signingKey = new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyID(withoutId.computeThumbprint().toString())
                    .build();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not compute key thumbprint", e);
        }
        if (publicKey.getModulus().bitLength() < 2048) {
            throw new IllegalStateException("JWT signing key must be at least 2048 bits");
        }
    }

    public RSAKey signingKey() {
        return signingKey;
    }

    public String keyId() {
        return signingKey.getKeyID();
    }

    public RSAPublicKey publicKey() {
        try {
            return signingKey.toRSAPublicKey();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Public keys only; safe to serve to anyone. */
    public JWKSet publicJwkSet() {
        return new JWKSet(signingKey.toPublicJWK());
    }

    private static RSAPrivateKey readPrivateKey(Resource location) {
        try (InputStream in = location.getInputStream()) {
            return RsaKeyConverters.pkcs8().convert(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read JWT private key", e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("JWT private key must be a PKCS#8 PEM (BEGIN PRIVATE KEY)", e);
        }
    }

    private static RSAPublicKey derivePublicKey(RSAPrivateKey privateKey) {
        if (!(privateKey instanceof RSAPrivateCrtKey crt)) {
            throw new IllegalStateException("JWT private key must include CRT parameters");
        }
        try {
            BigInteger modulus = crt.getModulus();
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(modulus, crt.getPublicExponent()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
