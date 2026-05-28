package com.acme.clipcascade.security.ecdh;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import javax.crypto.KeyAgreement;
import java.security.spec.X509EncodedKeySpec;
import java.security.KeyFactory;
import java.util.Base64;

/**
 * Elliptic Curve Diffie-Hellman (ECDH) service for Perfect Forward Secrecy.
 * Uses P-256 (secp256r1) curve for compatibility with client.
 */
@Slf4j
@Service
public class EcdhService {

    private static final String CURVE = "secp256r1";  // P-256
    private static final String ALGORITHM = "ECDH";
    private static final String KEY_FACTORY = "EC";
    private static final int KEY_SIZE = 32;  // 256 bits for AES-256
    private static final int SHARED_SECRET_SIZE = 32;  // P-256 produces 32 bytes

    /**
     * Generate ephemeral ECDH keypair (P-256).
     *
     * @return KeyPair with P-256 curve
     * @throws RuntimeException if key generation fails
     */
    public KeyPair generateKeypair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(KEY_FACTORY);
            ECGenParameterSpec ecSpec = new ECGenParameterSpec(CURVE);
            kpg.initialize(ecSpec);
            KeyPair keyPair = kpg.generateKeyPair();
            log.info("ECDH: Generated server keypair");
            return keyPair;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate ECDH keypair: " + e.getMessage(), e);
        }
    }

    /**
     * Perform ECDH key agreement with client's public key.
     *
     * @param ourPrivateKey   Server's private key
     * @param clientPublicKey Client's public key (from handshake)
     * @return 32-byte shared secret
     * @throws RuntimeException if key agreement fails
     */
    public byte[] computeSharedSecret(java.security.PrivateKey ourPrivateKey, PublicKey clientPublicKey) {
        try {
            KeyAgreement ka = KeyAgreement.getInstance(ALGORITHM);
            ka.init(ourPrivateKey);
            ka.doPhase(clientPublicKey, true);
            byte[] sharedSecret = ka.generateSecret();

            if (sharedSecret.length != SHARED_SECRET_SIZE) {
                throw new RuntimeException("Unexpected shared secret size: " + sharedSecret.length);
            }

            log.info("ECDH: Computed shared secret");
            return sharedSecret;
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute ECDH shared secret: " + e.getMessage(), e);
        }
    }

    /**
     * Derive AES-256 session key from ECDH shared secret using HKDF.
     *
     * @param sharedSecret ECDH shared secret (32 bytes)
     * @return 32-byte AES-256 encryption key
     * @throws RuntimeException if key derivation fails
     */
    public byte[] deriveSessionKey(byte[] sharedSecret) {
        try {
            // Use HKDF for key derivation (same as client)
            javax.crypto.Mac hmac = javax.crypto.Mac.getInstance("HmacSHA256");
            hmac.init(new javax.crypto.spec.SecretKeySpec(
                new byte[32],  // zero salt
                0,
                32,
                "HmacSHA256"
            ));

            byte[] prk = hmac.doFinal(sharedSecret);

            // Expand with info parameter
            String info = "clipboard-session";
            hmac.init(new javax.crypto.spec.SecretKeySpec(prk, "HmacSHA256"));
            byte[] sessionKey = hmac.doFinal(info.getBytes());

            // Return first 32 bytes
            byte[] result = new byte[KEY_SIZE];
            System.arraycopy(sessionKey, 0, result, 0, KEY_SIZE);

            log.info("ECDH: Derived session key");
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive session key: " + e.getMessage(), e);
        }
    }

    /**
     * Load public key from PEM string (client-provided).
     *
     * @param publicKeyPem PEM-encoded public key
     * @return PublicKey object
     * @throws RuntimeException if parsing fails
     */
    public PublicKey parsePublicKey(String publicKeyPem) {
        try {
            // Remove PEM headers/footers
            String keyData = publicKeyPem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");

            byte[] decodedKey = Base64.getDecoder().decode(keyData);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(decodedKey);
            KeyFactory kf = KeyFactory.getInstance(KEY_FACTORY);
            return kf.generatePublic(spec);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse public key: " + e.getMessage(), e);
        }
    }

    /**
     * Export public key to PEM format (send to client).
     *
     * @param publicKey PublicKey to export
     * @return PEM-encoded public key string
     */
    public String exportPublicKeyPem(PublicKey publicKey) {
        try {
            byte[] encoded = publicKey.getEncoded();
            String base64 = Base64.getEncoder().encodeToString(encoded);

            StringBuilder pem = new StringBuilder();
            pem.append("-----BEGIN PUBLIC KEY-----\n");
            for (int i = 0; i < base64.length(); i += 64) {
                pem.append(base64, i, Math.min(i + 64, base64.length())).append("\n");
            }
            pem.append("-----END PUBLIC KEY-----");

            return pem.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to export public key: " + e.getMessage(), e);
        }
    }
}
