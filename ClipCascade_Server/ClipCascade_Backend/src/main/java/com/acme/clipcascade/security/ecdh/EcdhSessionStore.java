package com.acme.clipcascade.security.ecdh;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Store for ephemeral ECDH-derived session keys.
 *
 * Keys are indexed by HTTP session ID.
 * Each entry auto-expires after 24 hours.
 * Scheduled cleanup runs hourly.
 */
@Slf4j
@Component
public class EcdhSessionStore {

    private static final long EXPIRY_MS = 24 * 60 * 60 * 1000;  // 24 hours
    private static final class StoredKey {
        byte[] key;
        long createdAt;

        StoredKey(byte[] key) {
            this.key = key;
            this.createdAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > EXPIRY_MS;
        }

        void clear() {
            if (key != null) {
                Arrays.fill(key, (byte) 0);  // Overwrite key bytes before disposal
            }
        }
    }

    private final ConcurrentMap<String, StoredKey> store = new ConcurrentHashMap<>();

    /**
     * Store a session key for the given HTTP session ID.
     *
     * @param sessionId HTTP session ID
     * @param sessionKey 32-byte AES-256 key
     * @throws IllegalArgumentException if sessionId is null or key length != 32
     */
    public void storeSessionKey(String sessionId, byte[] sessionKey) {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new IllegalArgumentException("Session ID cannot be null or empty");
        }
        if (sessionKey == null || sessionKey.length != 32) {
            throw new IllegalArgumentException("Session key must be 32 bytes");
        }

        store.put(sessionId, new StoredKey(sessionKey));
        log.debug("ECDH: Session key stored for sessionId={}", sessionId);
    }

    /**
     * Retrieve and remove a session key.
     *
     * @param sessionId HTTP session ID
     * @return 32-byte session key, or null if not found or expired
     */
    public byte[] getSessionKey(String sessionId) {
        if (sessionId == null) {
            return null;
        }

        StoredKey stored = store.get(sessionId);
        if (stored == null) {
            log.warn("ECDH: Session key not found for sessionId={}", sessionId);
            return null;
        }

        if (stored.isExpired()) {
            log.warn("ECDH: Session key expired for sessionId={}", sessionId);
            stored.clear();
            store.remove(sessionId);
            return null;
        }

        // Return a copy (caller responsible for clearing)
        byte[] keyCopy = new byte[32];
        System.arraycopy(stored.key, 0, keyCopy, 0, 32);
        return keyCopy;
    }

    /**
     * Remove and clear a session key (called on logout).
     *
     * @param sessionId HTTP session ID
     */
    public void removeSessionKey(String sessionId) {
        if (sessionId == null) {
            return;
        }

        StoredKey stored = store.remove(sessionId);
        if (stored != null) {
            stored.clear();
            log.debug("ECDH: Session key removed for sessionId={}", sessionId);
        }
    }

    /**
     * Cleanup expired entries (runs hourly).
     */
    @Scheduled(fixedDelay = 3600000)  // 1 hour
    public void cleanupExpiredKeys() {
        int removed = 0;
        for (String sessionId : store.keySet()) {
            StoredKey stored = store.get(sessionId);
            if (stored != null && stored.isExpired()) {
                stored.clear();
                store.remove(sessionId);
                removed++;
            }
        }
        if (removed > 0) {
            log.info("ECDH: Cleanup removed {} expired session keys", removed);
        }
    }

    /**
     * Get count of stored keys (for monitoring).
     */
    public int size() {
        return store.size();
    }
}
