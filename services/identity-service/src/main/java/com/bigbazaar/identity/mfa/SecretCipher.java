package com.bigbazaar.identity.mfa;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM for TOTP secrets and seller KYC inputs. Unlike passwords they must be recoverable to verify codes, so they
 * are encrypted, not hashed. Output is {@code v1:base64(iv || ciphertext+tag)}; the prefix leaves room
 * for key rotation.
 */
@Component
public class SecretCipher {

    private static final String VERSION = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    SecretCipher(MfaProperties properties) {
        this.key = new SecretKeySpec(properties.encryptionKeyBytes(), "AES");
    }

    public String encrypt(byte[] plaintext, String associatedData) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData.getBytes());
            byte[] sealed = cipher.doFinal(plaintext);
            return VERSION + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /** {@code associatedData} binds the ciphertext to its owner, so rows can't be swapped between users. */
    public byte[] decrypt(String stored, String associatedData) {
        if (!stored.startsWith(VERSION)) {
            throw new IllegalStateException("Unknown secret format");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(VERSION.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            cipher.updateAAD(associatedData.getBytes());
            return cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }
}
