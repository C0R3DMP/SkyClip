package com.acme.clipcascade.security.ecdh;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EcdhServiceTest {

    private final EcdhService ecdhService = new EcdhService();

    /**
     * The desktop client derives its session key with `cryptography`'s HKDF
     * (RFC 5869): salt = 32 zero bytes, info = "clipboard-session". The server
     * must produce byte-for-byte the same output for the same shared secret or
     * no two devices can ever agree on a session key. This test recomputes the
     * expected RFC 5869 value independently (including the block-counter byte
     * the implementation must append) and compares it against
     * EcdhService#deriveSessionKey.
     */
    @Test
    void derivedKeyMatchesRfc5869Hkdf() throws Exception {
        byte[] sharedSecret = new byte[32];
        for (int i = 0; i < sharedSecret.length; i++) {
            sharedSecret[i] = (byte) i;
        }

        byte[] expected = rfc5869HkdfSha256(sharedSecret, new byte[32], "clipboard-session".getBytes(StandardCharsets.UTF_8), 32);

        byte[] actual = ecdhService.deriveSessionKey(sharedSecret);

        assertArrayEquals(expected, actual,
                "Server HKDF output diverged from RFC 5869 — desktop and server would derive different session keys");
    }

    @Test
    void parsePublicKeyAcceptsP256Key() {
        KeyPair keyPair = ecdhService.generateKeypair();
        String pem = ecdhService.exportPublicKeyPem(keyPair.getPublic());

        PublicKey parsed = ecdhService.parsePublicKey(pem);

        assertNotNull(parsed);
    }

    /**
     * A handshake request supplies a raw PEM public key with no proof of
     * curve — parsePublicKey must reject keys from any curve other than
     * P-256 (invalid-curve attack surface), rather than handing them straight
     * to KeyAgreement.
     */
    @Test
    void parsePublicKeyRejectsNonP256Curve() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair otherCurveKeyPair = kpg.generateKeyPair();
        String pem = ecdhService.exportPublicKeyPem(otherCurveKeyPair.getPublic());

        assertThrows(RuntimeException.class, () -> ecdhService.parsePublicKey(pem));
    }

    private static byte[] rfc5869HkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length) throws Exception {
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = hmac.doFinal(ikm);

        hmac.init(new SecretKeySpec(prk, "HmacSHA256"));
        hmac.update(info);
        hmac.update((byte) 0x01);
        byte[] t1 = hmac.doFinal();

        byte[] okm = new byte[length];
        System.arraycopy(t1, 0, okm, 0, length);
        return okm;
    }
}
