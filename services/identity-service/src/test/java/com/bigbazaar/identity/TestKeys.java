package com.bigbazaar.identity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** Throwaway RSA signing key and MFA encryption key, created once per test JVM. */
public final class TestKeys {

    public static final Path PRIVATE_KEY = writePrivateKey();
    /** A retired signing key: its public half is configured as a previous key, the pair signs test tokens. */
    public static final KeyPair PREVIOUS_KEY = newKeyPair();
    public static final Path PREVIOUS_PUBLIC_KEY = writePem("PUBLIC KEY", PREVIOUS_KEY.getPublic().getEncoded());
    public static final String MFA_KEY = randomBase64(32);
    public static final Path STORAGE_DIR = tempDir();

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("storage-test-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path writePem(String type, byte[] der) {
        try {
            String pem = "-----BEGIN " + type + "-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                    + "\n-----END " + type + "-----\n";
            Path file = Files.createTempFile("jwt-test-", ".pem");
            Files.writeString(file, pem);
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

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
