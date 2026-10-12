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
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.stereotype.Component;

/**
 * Loads the RSA signing key and derives its public half, so only one file has to be provisioned.
 * The key ID is the RFC 7638 thumbprint: it changes automatically when the key is rotated.
 * Retired public keys (after a rotation) are still published and accepted until they are removed.
 */
@Component
public class SigningKeys {

    private static final Logger log = LoggerFactory.getLogger(SigningKeys.class);

    private final RSAKey signingKey;
    private final List<RSAKey> previousKeys = new ArrayList<>();

    public SigningKeys(JwtProperties properties) {
        RSAPrivateKey privateKey = readPrivateKey(properties.privateKeyLocation());
        RSAPublicKey publicKey = derivePublicKey(privateKey);
        this.signingKey = describe(publicKey, privateKey);
        var loader = new DefaultResourceLoader();
        for (String location : properties.previousPublicKeyLocations()) {
            RSAKey previous = describe(readPublicKey(loader.getResource(location)), null);
            if (!previous.getKeyID().equals(signingKey.getKeyID())) {
                previousKeys.add(previous);
                log.info("Still accepting tokens from retired signing key kid={}", previous.getKeyID());
            }
        }
        log.info("Signing access tokens with kid={}", signingKey.getKeyID());
    }

    public RSAKey signingKey() {
        return signingKey;
    }

    public String keyId() {
        return signingKey.getKeyID();
    }

    /** Public keys only, current first; safe to serve to anyone. */
    public JWKSet publicJwkSet() {
        List<com.nimbusds.jose.jwk.JWK> keys = new ArrayList<>();
        keys.add(signingKey.toPublicJWK());
        previousKeys.forEach(keys::add);
        return new JWKSet(keys);
    }

    private static RSAKey describe(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
        if (publicKey.getModulus().bitLength() < 2048) {
            throw new IllegalStateException("JWT signing keys must be at least 2048 bits");
        }
        try {
            RSAKey withoutId = new RSAKey.Builder(publicKey).build();
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyID(withoutId.computeThumbprint().toString())
                    .build();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not compute key thumbprint", e);
        }
    }

    private static RSAPublicKey readPublicKey(Resource location) {
        try (InputStream in = location.getInputStream()) {
            return RsaKeyConverters.x509().convert(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read previous JWT public key " + location, e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Previous JWT keys must be public key PEMs (BEGIN PUBLIC KEY)", e);
        }
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
