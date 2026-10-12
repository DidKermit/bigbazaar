package com.bigbazaar.identity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** Throwaway RSA signing key and MFA encryption key, created once per test JVM. */
public final class TestKeys {

    public static final Path PRIVATE_KEY = writePrivateKey();
    public static final String MFA_KEY = randomBase64(32);

    private static Path writePrivateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            byte[] pkcs8 = generator.generateKeyPair().getPrivate().getEncoded();
            String pem = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pkcs8)
                    + "\n-----END PRIVATE KEY-----\n";
            Path file = Files.createTempFile("jwt-test-", ".pem");
            Files.writeString(file, pem);
            file.toFile().deleteOnExit();
            return file;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String randomBase64(int bytes) {
        byte[] value = new byte[bytes];
        new SecureRandom().nextBytes(value);
        return Base64.getEncoder().encodeToString(value);
    }

    private TestKeys() {
    }
}
