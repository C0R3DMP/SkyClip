package com.acme.clipcascade.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.acme.clipcascade.service.LoginAttemptService;

@Component
public class LoginAttemptScheduler {

    private static final Logger logger = LoggerFactory.getLogger(LoginAttemptScheduler.class);

    @Autowired
    private LoginAttemptService loginAttemptService;

    @Scheduled(fixedDelay = 3600000)
    public void cleanupOldAttempts() {
        try {
            logger.info("Starting cleanup of login attempts older than 24 hours");
            int deletedCount = loginAttemptService.cleanupOldAttempts(24);
            logger.info("Cleanup complete: deleted {} old login attempt records", deletedCount);
        } catch (Exception e) {
            logger.error("Error during login attempt cleanup", e);
        }
    }
}
