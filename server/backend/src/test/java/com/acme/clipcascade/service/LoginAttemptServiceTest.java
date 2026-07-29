package com.acme.clipcascade.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the "Unlock User" admin action.
 *
 * The DB-backed LoginAttemptService (wired via LoginAttemptFilter /
 * LoginAttemptFailureHandler in SecurityConfiguration) is what actually
 * locks a user out — it's the thing returning HTTP 429. The separate
 * BruteForceProtectionService has its own admin-facing unlock endpoint, but
 * nothing in the app ever calls its recordAndValidateAttempt(), so its
 * internal tracker is always empty and its unlockUser() always operates on
 * a username that was never tracked. Before this fix, hitting
 * /admin/unlock-user cleared only that dead tracker, so a genuinely
 * locked-out user (locked out via LoginAttemptService) stayed locked out
 * despite the admin UI reporting success.
 *
 * This test exercises LoginAttemptService directly: lock a user out via
 * repeated recordFailure() calls (mirroring what LoginAttemptFailureHandler
 * does on each bad login), confirm isLockedOut() is true, then confirm
 * manualUnlockByUsername() actually clears it.
 */
// RANDOM_PORT (not the MOCK default): StompWebSocketConfig registers a
// ServletServerContainerFactoryBean that requires a real embedded servlet
// container (jakarta.websocket.server.ServerContainer), which a mock web
// environment doesn't provide. SkyClipApplicationTests uses the same setting
// for the same reason.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
@DirtiesContext
class LoginAttemptServiceTest {

    @Autowired
    private LoginAttemptService loginAttemptService;

    @Test
    void manualUnlockByUsernameClearsARealLockout() {
        String username = "unlock-test-" + UUID.randomUUID();
        String ip = "203.0.113.5";

        // application.properties default: security.rate-limit.max-attempts=5
        for (int i = 0; i < 5; i++) {
            loginAttemptService.recordFailure(username, ip);
        }

        assertTrue(loginAttemptService.isLockedOut(username, ip),
                "5 recorded failures should lock the user out (matches the real login path)");

        int deleted = loginAttemptService.manualUnlockByUsername(username);

        assertEquals(5, deleted, "unlock should delete every recorded failure for this username");
        assertFalse(loginAttemptService.isLockedOut(username, ip),
                "user must no longer be locked out after manualUnlockByUsername");
    }

    @Test
    void manualUnlockByUsernameClearsLockoutAcrossDifferentIps() {
        // isLockedOut() locks a username out globally across ANY ip
        // (failuresForUsername >= maxAttempts), not just per-ip. The admin
        // "Unlock User" form only has a username field (no ip), so the
        // unlock must clear failures regardless of which ip they came from.
        String username = "unlock-test-multi-ip-" + UUID.randomUUID();

        loginAttemptService.recordFailure(username, "203.0.113.10");
        loginAttemptService.recordFailure(username, "203.0.113.11");
        loginAttemptService.recordFailure(username, "203.0.113.12");
        loginAttemptService.recordFailure(username, "203.0.113.13");
        loginAttemptService.recordFailure(username, "203.0.113.14");

        assertTrue(loginAttemptService.isLockedOut(username, "203.0.113.99"),
                "username-level lockout applies even from an ip that made no failed attempts");

        loginAttemptService.manualUnlockByUsername(username);

        assertFalse(loginAttemptService.isLockedOut(username, "203.0.113.99"));
    }

    @Test
    void manualUnlockByUsernameOnUnknownUserIsHarmless() {
        int deleted = loginAttemptService.manualUnlockByUsername("no-such-user-" + UUID.randomUUID());
        assertEquals(0, deleted);
    }
}
