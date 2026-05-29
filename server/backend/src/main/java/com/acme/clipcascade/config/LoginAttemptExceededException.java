package com.acme.clipcascade.config;

import org.springframework.security.core.AuthenticationException;

public class LoginAttemptExceededException extends AuthenticationException {
    public LoginAttemptExceededException(String msg) {
        super(msg);
    }

    public LoginAttemptExceededException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
