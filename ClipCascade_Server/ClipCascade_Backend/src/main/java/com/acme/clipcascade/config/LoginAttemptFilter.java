package com.acme.clipcascade.config;

import java.io.IOException;
import java.time.Duration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import com.acme.clipcascade.service.LoginAttemptService;

public class LoginAttemptFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(LoginAttemptFilter.class);

    private final LoginAttemptService loginAttemptService;

    public LoginAttemptFilter(LoginAttemptService loginAttemptService) {
        this.loginAttemptService = loginAttemptService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if ("POST".equalsIgnoreCase(request.getMethod()) && "/login".equals(request.getServletPath())) {
            String username = request.getParameter("username");
            String clientIp = getClientIpAddress(request);

            if (loginAttemptService.isLockedOut(username, clientIp)) {
                Duration timeRemaining = loginAttemptService.getTimeUntilUnlock(username, clientIp);
                long minutesRemaining = Math.max(1, timeRemaining.toMinutes());

                logger.warn("Login blocked by rate limiter: username='{}', ip='{}', minutes_until_unlock={}",
                        username, clientIp, minutesRemaining);

                response.setStatus(429);
                response.setContentType("text/plain");
                response.getWriter().write(
                        String.format("Too many attempts. Try again in %d minutes.", minutesRemaining));
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        return request.getRemoteAddr();
    }
}
