package com.acme.clipcascade.utils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/**
 * Server-side cipher manager for AES-256-GCM encryption/decryption.
 *
 * Uses ECDH-derived session keys for in-transit message decryption.
 * (At-rest storage is handled by client-side cipher_manager.py)
 */
@Slf4j
@Component
public class CipherManager {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;

    /**
     * Decrypt in-transit message using ECDH session key.
     *
     * @param sessionKey 32-byte AES-256 key (ECDH-derived)
     * @param nonce 12-byte GCM nonce
     * @param ciphertext Encrypted message bytes
     * @param tag 16-byte GCM authentication tag
     * @return Decrypted plaintext string
     * @throws RuntimeException if decryption fails
     */
    public String decryptTransit(byte[] sessionKey, byte[] nonce, byte[] ciphertext, byte[] tag) {
        try {
            if (sessionKey == null || sessionKey.length != 32) {
                throw new IllegalArgumentException("Session key must be 32 bytes");
            }
            if (nonce == null || nonce.length != GCM_IV_LENGTH_BYTES) {
                throw new IllegalArgumentException("Nonce must be 12 bytes");
            }
            if (tag == null || tag.length != 16) {
                throw new IllegalArgumentException("Tag must be 16 bytes");
            }

            // Combine ciphertext + tag for GCM decryption
            byte[] ciphertextWithTag = new byte[ciphertext.length + tag.length];
            System.arraycopy(ciphertext, 0, ciphertextWithTag, 0, ciphertext.length);
            System.arraycopy(tag, 0, ciphertextWithTag, ciphertext.length, tag.length);

            // Initialize cipher
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(sessionKey, 0, 32, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

            // Decrypt
            byte[] plaintext = cipher.doFinal(ciphertextWithTag);
            return new String(plaintext, "UTF-8");

        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt message: " + e.getMessage(), e);
        }
    }

    /**
     * Encrypt in-transit message using ECDH session key (for server → client).
     *
     * @param sessionKey 32-byte AES-256 key (ECDH-derived)
     * @param plaintext Message to encrypt
     * @return Map with "nonce", "ciphertext", "tag" (all hex-encoded)
     * @throws RuntimeException if encryption fails
     */
    public Map<String, String> encryptTransit(byte[] sessionKey, String plaintext) {
        try {
            if (sessionKey == null || sessionKey.length != 32) {
                throw new IllegalArgumentException("Session key must be 32 bytes");
            }

            // Generate random nonce
            byte[] nonce = new byte[GCM_IV_LENGTH_BYTES];
            SecureRandom random = new SecureRandom();
            random.nextBytes(nonce);

            // Initialize cipher
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(sessionKey, 0, 32, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

            // Encrypt
            byte[] plaintextBytes = plaintext.getBytes("UTF-8");
            byte[] ciphertextWithTag = cipher.doFinal(plaintextBytes);

            // Split ciphertext and tag
            byte[] ciphertext = new byte[ciphertextWithTag.length - 16];
            byte[] tag = new byte[16];
            System.arraycopy(ciphertextWithTag, 0, ciphertext, 0, ciphertext.length);
            System.arraycopy(ciphertextWithTag, ciphertext.length, tag, 0, 16);

            // Return as hex-encoded JSON-compatible map
            Map<String, String> result = new HashMap<>();
            result.put("nonce", bytesToHex(nonce));
            result.put("ciphertext", bytesToHex(ciphertext));
            result.put("tag", bytesToHex(tag));
            return result;

        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt message: " + e.getMessage(), e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
