package com.acme.clipcascade.controller;

import com.acme.clipcascade.security.ecdh.EcdhService;
import com.acme.clipcascade.security.ecdh.EcdhSessionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;

/**
 * ECDH key-exchange endpoint. Derives a shared session key and stores it
 * per HTTP session.
 *
 * NOTE: transit encryption is incomplete — the derived key is stored but
 * the WebSocket message handler does not yet retrieve or apply it.
 * E2E confidentiality currently relies on the shared master key (at-rest).
 * Full device-to-device in-transit encryption is planned for a future release.
 */
@Slf4j
@RestController
@RequestMapping("/api/ecdh")
@RequiredArgsConstructor
public class EcdhController {

    private final EcdhService ecdhService;
    private final EcdhSessionStore ecdhSessionStore;

    /**
     * POST /api/ecdh/handshake
     *
     * Request:
     *   {
     *     "public_key": "-----BEGIN PUBLIC KEY-----\n...PEM...\n-----END PUBLIC KEY-----"
     *   }
     *
     * Response:
     *   {
     *     "public_key": "-----BEGIN PUBLIC KEY-----\n...PEM...\n-----END PUBLIC KEY-----"
     *   }
     *
     * @param request Client ECDH handshake request
     * @param httpRequest HTTP request (for session ID)
     * @return Server's public key for key agreement
     */
    @PostMapping("/handshake")
    public ResponseEntity<Map<String, Object>> handshake(
            @RequestBody EcdhHandshakeRequest request,
            HttpServletRequest httpRequest
    ) {
        try {
            log.info("ECDH: Handshake initiated");

            // Step 1: Get HTTP session ID
            String sessionId = httpRequest.getSession().getId();

            // Step 2: Parse client's public key
            PublicKey clientPublicKey = ecdhService.parsePublicKey(request.public_key);

            // Step 3: Generate server keypair
            KeyPair serverKeyPair = ecdhService.generateKeypair();

            // Step 4: Compute shared secret with client's public key
            byte[] sharedSecret = ecdhService.computeSharedSecret(
                serverKeyPair.getPrivate(),
                clientPublicKey
            );

            // Step 5: Derive session key
            byte[] sessionKey = ecdhService.deriveSessionKey(sharedSecret);

            // Step 6: STORE session key in EcdhSessionStore (for WebSocket handler to use)
            ecdhSessionStore.storeSessionKey(sessionId, sessionKey);

            // Step 7: Export server's public key
            String serverPublicKeyPem = ecdhService.exportPublicKeyPem(serverKeyPair.getPublic());

            log.info("ECDH: Handshake successful, session key stored for sessionId={}", sessionId);

            // Response: server's public key
            Map<String, Object> response = new HashMap<>();
            response.put("public_key", serverPublicKeyPem);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            // Log full detail server-side; return a generic message so internal
            // exception text is never leaked to the client.
            log.error("ECDH: Handshake failed", e);
            return ResponseEntity.status(400).body(
                Map.of("error", "ECDH handshake failed")
            );
        }
    }

    /**
     * Request DTO for ECDH handshake.
     */
    public static class EcdhHandshakeRequest {
        public String public_key;
    }
}
