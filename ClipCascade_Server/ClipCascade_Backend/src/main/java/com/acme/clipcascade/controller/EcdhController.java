package com.acme.clipcascade.controller;

import com.acme.clipcascade.security.ecdh.EcdhService;
import com.acme.clipcascade.security.ecdh.EcdhSessionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;

/**
 * REST endpoint for Perfect Forward Secrecy (ECDH) handshake.
 *
 * Flow:
 * 1. Client generates keypair, sends public key
 * 2. Server generates keypair, computes shared secret with client's public key
 * 3. Server derives session key, STORES it, sends its public key back
 * 4. Client receives server's public key, computes same shared secret
 * 5. Both derive identical session key for in-transit encryption
 * 6. WebSocket handler retrieves stored key for message decryption
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
            PublicKey clientPublicKey = ecdhService.parsePublicKey(request.getPublic_key());

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
            log.error("ECDH: Handshake failed: {}", e.getMessage());
            return ResponseEntity.status(400).body(
                Map.of("error", "ECDH handshake failed: " + e.getMessage())
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
