package com.acme.clipcascade.config;

import java.io.IOException;
import java.time.Duration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.util.WebUtils;

import com.acme.clipcascade.service.LoginAttemptService;

public class LoginAttemptFilter extends UsernamePasswordAuthenticationFilter {

    private static final Logger logger = LoggerFactory.getLogger(LoginAttemptFilter.class);

    private final LoginAttemptService loginAttemptService;

    public LoginAttemptFilter(LoginAttemptService loginAttemptService) {
        this.loginAttemptService = loginAttemptService;
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
            throws AuthenticationException {

        String username = request.getParameter("username");
        String clientIp = getClientIpAddress(request);

        if (loginAttemptService.isLockedOut(username, clientIp)) {
            Duration timeRemaining = loginAttemptService.getTimeUntilUnlock(username, clientIp);
            long minutesRemaining = timeRemaining.toMinutes();
            String errorMsg = String.format("Too many attempts. Try again in %d minutes.", minutesRemaining);

            logger.warn("Login blocked due to rate limiting: username='{}', ip='{}', minutes_until_unlock={}",
                    username, clientIp, minutesRemaining);

            throw new LoginAttemptExceededException(errorMsg);
        }

        return super.attemptAuthentication(request, response);
    }

    @Override
    protected void successfulAuthentication(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain,
            Authentication authResult) throws IOException, ServletException {

        String username = authResult.getName();
        String clientIp = getClientIpAddress(request);

        loginAttemptService.recordSuccess(username, clientIp);
        super.successfulAuthentication(request, response, chain, authResult);
    }

    @Override
    protected void unsuccessfulAuthentication(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException failed) throws IOException, ServletException {

        String username = request.getParameter("username");
        String clientIp = getClientIpAddress(request);

        loginAttemptService.recordFailure(username, clientIp);
        super.unsuccessfulAuthentication(request, response, failed);
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
