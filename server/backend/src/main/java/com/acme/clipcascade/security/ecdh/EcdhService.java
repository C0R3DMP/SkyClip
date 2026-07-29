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
    private static final int HMAC_SHA256_LENGTH = 32;
    private static final String HKDF_INFO = "clipboard-session";
    // Field size of P-256, used to reject public keys from any other curve.
    private static final int P256_FIELD_SIZE_BITS = 256;

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
     * Derive AES-256 session key from ECDH shared secret using HKDF-SHA256
     * (RFC 5869) with an all-zero salt and info = "clipboard-session".
     *
     * This must stay byte-for-byte identical to the desktop client, which uses
     * `cryptography`'s HKDF (salt=None, info=b"clipboard-session"). The
     * expand step therefore has to append the RFC 5869 block counter (0x01) to
     * the info string; omitting it — as an earlier version of this method did —
     * yields a completely different key and no client can ever agree with the
     * server. See EcdhServiceTest#derivedKeyMatchesRfc5869Hkdf.
     *
     * @param sharedSecret ECDH shared secret (32 bytes)
     * @return 32-byte AES-256 encryption key
     * @throws RuntimeException if key derivation fails
     */
    public byte[] deriveSessionKey(byte[] sharedSecret) {
        try {
            javax.crypto.Mac hmac = javax.crypto.Mac.getInstance("HmacSHA256");

            // HKDF-Extract: PRK = HMAC(salt, IKM), salt = HashLen zero bytes
            hmac.init(new javax.crypto.spec.SecretKeySpec(
                new byte[HMAC_SHA256_LENGTH],  // zero salt, per RFC 5869 default
                "HmacSHA256"
            ));
            byte[] prk = hmac.doFinal(sharedSecret);

            // HKDF-Expand: T(1) = HMAC(PRK, T(0) | info | 0x01), T(0) = empty.
            // KEY_SIZE (32) <= HashLen (32), so a single block is enough.
            byte[] info = HKDF_INFO.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] expandInput = new byte[info.length + 1];
            System.arraycopy(info, 0, expandInput, 0, info.length);
            expandInput[info.length] = 0x01;  // RFC 5869 block counter

            hmac.init(new javax.crypto.spec.SecretKeySpec(prk, "HmacSHA256"));
            byte[] okm = hmac.doFinal(expandInput);

            byte[] result = new byte[KEY_SIZE];
            System.arraycopy(okm, 0, result, 0, KEY_SIZE);

            java.util.Arrays.fill(prk, (byte) 0);
            java.util.Arrays.fill(okm, (byte) 0);

            log.info("ECDH: Derived session key");
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive session key: " + e.getMessage(), e);
        }
    }

    /**
     * Load public key from PEM string (client-provided).
     *
     * The input is fully attacker-controlled, so the parsed key is checked to
     * be an EC key on P-256 before it is ever handed to KeyAgreement. Without
     * that check a caller can submit a key on an arbitrary (possibly weak or
     * malformed) curve — the classic invalid-curve setup — and at best force a
     * confusing failure deep inside the JCE provider.
     *
     * @param publicKeyPem PEM-encoded public key
     * @return PublicKey object, guaranteed to be an EC key on P-256
     * @throws RuntimeException if parsing fails or the key is not on P-256
     */
    public PublicKey parsePublicKey(String publicKeyPem) {
        PublicKey publicKey;
        try {
            // Remove PEM headers/footers
            String keyData = publicKeyPem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");

            byte[] decodedKey = Base64.getDecoder().decode(keyData);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(decodedKey);
            KeyFactory kf = KeyFactory.getInstance(KEY_FACTORY);
            publicKey = kf.generatePublic(spec);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse public key: " + e.getMessage(), e);
        }

        if (!(publicKey instanceof java.security.interfaces.ECPublicKey ecPublicKey)) {
            throw new RuntimeException(
                    "Public key is not an EC key: " + publicKey.getAlgorithm());
        }

        java.security.spec.ECParameterSpec params = ecPublicKey.getParams();
        if (params == null
                || params.getCurve() == null
                || params.getCurve().getField() == null
                || params.getCurve().getField().getFieldSize() != P256_FIELD_SIZE_BITS
                || !params.getCurve().equals(p256Params().getCurve())
                || !params.getGenerator().equals(p256Params().getGenerator())
                || !params.getOrder().equals(p256Params().getOrder())
                || params.getCofactor() != p256Params().getCofactor()) {

            throw new RuntimeException("Public key is not on the P-256 curve");
        }

        return publicKey;
    }

    /**
     * Canonical P-256 domain parameters, resolved once from the JCE provider.
     */
    private java.security.spec.ECParameterSpec p256Params() {
        if (cachedP256Params == null) {
            try {
                java.security.AlgorithmParameters ap =
                        java.security.AlgorithmParameters.getInstance(KEY_FACTORY);
                ap.init(new ECGenParameterSpec(CURVE));
                cachedP256Params = ap.getParameterSpec(java.security.spec.ECParameterSpec.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to resolve P-256 parameters: " + e.getMessage(), e);
            }
        }
        return cachedP256Params;
    }

    private volatile java.security.spec.ECParameterSpec cachedP256Params;

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
