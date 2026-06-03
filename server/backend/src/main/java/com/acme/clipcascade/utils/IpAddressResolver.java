package com.acme.clipcascade.utils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.acme.clipcascade.config.AppProperties;

import jakarta.servlet.http.HttpServletRequest;

@Component
public class IpAddressResolver {

    private final List<IpAddressMatcher> trustedProxyMatchers;

    public IpAddressResolver(AppProperties appProperties) {
        String cidrs = appProperties.getTrustedProxyCidrs();
        if (cidrs == null || cidrs.isBlank()) {
            this.trustedProxyMatchers = Collections.emptyList();
        } else {
            this.trustedProxyMatchers = Arrays.stream(cidrs.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(IpAddressMatcher::new)
                    .collect(Collectors.toList());
        }
    }

    /**
     * Resolve the real client IP from the given request.
     *
     * Default (no CC_TRUSTED_PROXY_CIDRS set): always returns getRemoteAddr(),
     * ignoring all forwarded headers — an attacker cannot spoof their IP.
     *
     * With trusted proxies configured: if the direct TCP address is a trusted proxy,
     * walks X-Forwarded-For right-to-left and returns the first non-trusted IP.
     * The leftmost entries are attacker-controlled and are never used.
     * If the direct address is NOT a trusted proxy, forwarded headers are ignored.
     */
    public String getUserIpAddress(HttpServletRequest request) {
        if (trustedProxyMatchers.isEmpty()) {
            return request.getRemoteAddr();
        }

        String remoteAddr = request.getRemoteAddr();
        if (!isTrusted(remoteAddr)) {
            return remoteAddr;
        }

        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor == null || xForwardedFor.isBlank()) {
            return remoteAddr;
        }

        // Walk right-to-left: rightmost non-trusted IP is the real client.
        // The leftmost entries are attacker-supplied and intentionally skipped.
        String[] parts = xForwardedFor.split(",");
        for (int i = parts.length - 1; i >= 0; i--) {
            String ip = parts[i].trim();
            if (!ip.isEmpty() && !isTrusted(ip)) {
                return ip;
            }
        }

        return remoteAddr;
    }

    /**
     * Convenience overload using the current request from RequestContextHolder.
     * For service-layer code that does not have direct access to HttpServletRequest.
     */
    public String getUserIpAddress() {
        Object requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes == null) {
            return "0.0.0.0";
        }
        HttpServletRequest request = ((ServletRequestAttributes) requestAttributes).getRequest();
        return getUserIpAddress(request);
    }

    private boolean isTrusted(String ipAddress) {
        return trustedProxyMatchers.stream().anyMatch(m -> m.matches(ipAddress));
    }
}
