package com.acme.clipcascade.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@ConditionalOnProperty(prefix = "app.p2p", name = "enabled", havingValue = "true", matchIfMissing = false)
public class P2PWebSocketConfig implements WebSocketConfigurer {

    private final P2PWebSocketHandler p2pWebSocketHandler;
    private final AppProperties appProperties;

    public P2PWebSocketConfig(
            P2PWebSocketHandler p2pWebSocketHandler,
            AppProperties appProperties) {

        this.p2pWebSocketHandler = p2pWebSocketHandler;
        this.appProperties = appProperties;
    }

    @Override
    public void registerWebSocketHandlers(@NonNull WebSocketHandlerRegistry registry) {
        registry.addHandler(p2pWebSocketHandler, "/p2psignaling")
                .setAllowedOrigins(appProperties.getAllowedOrigins());
    }
}
