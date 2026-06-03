package com.acme.clipcascade.config;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;

import com.acme.clipcascade.service.LoginAttemptService;
import com.acme.clipcascade.utils.IpAddressResolver;

public class LoginAttemptFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private final LoginAttemptService loginAttemptService;
    private final IpAddressResolver ipAddressResolver;

    public LoginAttemptFailureHandler(LoginAttemptService loginAttemptService,
            IpAddressResolver ipAddressResolver) {
        super("/login?error");
        this.loginAttemptService = loginAttemptService;
        this.ipAddressResolver = ipAddressResolver;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException, ServletException {

        String username = request.getParameter("username");
        String clientIp = ipAddressResolver.getUserIpAddress(request);
        if (username != null && !username.isEmpty()) {
            loginAttemptService.recordFailure(username, clientIp);
        }

        super.onAuthenticationFailure(request, response, exception);
    }
}
