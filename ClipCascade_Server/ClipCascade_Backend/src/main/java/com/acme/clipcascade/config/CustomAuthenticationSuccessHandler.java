package com.acme.clipcascade.config;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import com.acme.clipcascade.service.FacadeUserService;

public class CustomAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final FacadeUserService facadeUserService;

    public CustomAuthenticationSuccessHandler(FacadeUserService facadeUserService) {
        this.facadeUserService = facadeUserService;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException {

        facadeUserService.setLoginDetails(authentication.getName());
        response.sendRedirect("/");
    }
}
