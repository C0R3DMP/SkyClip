package com.acme.clipcascade.service;

import java.time.LocalDateTime;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.acme.clipcascade.model.LoginAttempt;
import com.acme.clipcascade.repo.LoginAttemptRepo;

@Service
public class LoginAttemptService {

    private static final Logger logger = LoggerFactory.getLogger(LoginAttemptService.class);

    private final LoginAttemptRepo loginAttemptRepo;

    @Value("${security.rate-limit.max-attempts:5}")
    private int maxAttempts;

    @Value("${security.rate-limit.lockout-minutes:15}")
    private int lockoutMinutes;

    @Value("${security.rate-limit.cleanup-hours:24}")
    private int cleanupHours;

    public LoginAttemptService(LoginAttemptRepo loginAttemptRepo) {
        this.loginAttemptRepo = loginAttemptRepo;
    }

    public boolean isLockedOut(String username, String ipAddress) {
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(lockoutMinutes);

        int failuresForUsername = loginAttemptRepo.countFailuresForUsernameAfter(username, cutoffTime);
        int failuresForIp = loginAttemptRepo.countFailuresForIpAfter(ipAddress, cutoffTime);

        boolean lockedByUsername = failuresForUsername >= maxAttempts;
        boolean lockedByIp = failuresForIp >= maxAttempts;

        if (lockedByUsername || lockedByIp) {
            logger.warn("Login attempt blocked for username='{}', ip='{}' (username_failures={}, ip_failures={})",
                    username, ipAddress, failuresForUsername, failuresForIp);
            return true;
        }

        return false;
    }

    public Duration getTimeUntilUnlock(String username, String ipAddress) {
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(lockoutMinutes);
        LocalDateTime oldestFailureTime = loginAttemptRepo.getOldestFailureTimestamp(username, ipAddress, cutoffTime);

        if (oldestFailureTime == null) {
            return Duration.ZERO;
        }

        LocalDateTime unlockTime = oldestFailureTime.plusMinutes(lockoutMinutes);
        Duration remaining = Duration.between(LocalDateTime.now(), unlockTime);

        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    public void recordFailure(String username, String ipAddress) {
        LoginAttempt attempt = new LoginAttempt(username, ipAddress, "LOGIN_FAILURE", LocalDateTime.now());
        loginAttemptRepo.save(attempt);
        logger.info("Login failure recorded for username='{}', ip='{}'", username, ipAddress);
    }

    public void recordSuccess(String username, String ipAddress) {
        LoginAttempt attempt = new LoginAttempt(username, ipAddress, "LOGIN_SUCCESS", LocalDateTime.now());
        loginAttemptRepo.save(attempt);
        logger.debug("Login success recorded for username='{}', ip='{}'", username, ipAddress);
    }

    public void manualUnlock(String username, String ipAddress) {
        int deletedCount = loginAttemptRepo.deleteByUsernameAndIpAddress(username, ipAddress);
        logger.warn("Admin manual unlock for username='{}', ip='{}'. Deleted {} attempts.",
                username, ipAddress, deletedCount);
    }

    public int cleanupOldAttempts(int hoursOld) {
        LocalDateTime cutoffTime = LocalDateTime.now().minusHours(hoursOld);
        int deletedCount = loginAttemptRepo.deleteOlderThan(cutoffTime);
        logger.info("Cleanup: deleted {} login attempts older than {} hours", deletedCount, hoursOld);
        return deletedCount;
    }
}
