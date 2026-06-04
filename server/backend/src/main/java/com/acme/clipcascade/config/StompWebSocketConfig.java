package com.acme.clipcascade.config;

import org.springframework.lang.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import com.acme.clipcascade.constants.ServerConstants;

@Configuration
@EnableWebSocketMessageBroker
@ConditionalOnProperty(prefix = "app.p2p", name = "enabled", havingValue = "false", matchIfMissing = false)
public class StompWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final AppProperties appProperties;

    public StompWebSocketConfig(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Override
    public void configureMessageBroker(@NonNull MessageBrokerRegistry config) {
        // Prefix for client-to-server communication (application-level messages).
        // Clients send messages to server endpoints starting with "/app".
        config.setApplicationDestinationPrefixes("/app");

        // Prefix for user-specific destinations.
        // Used when sending messages to specific users via "/user/{sessionId}/...".
        config.setUserDestinationPrefix("/user");

        if (!appProperties.isExternalBrokerEnabled()) {
            /**
             * Enables a simple in-memory message broker to handle subscription-based
             * messaging.
             * - Subscriptions (sending messages to users) are created with prefixes like:
             * "/user/{sessionId}/queue/.." for user-specific destinations.
             */
            config.enableSimpleBroker("/queue") // <- destination
                    .setHeartbeatValue(new long[] {
                            ServerConstants.HEARTBEAT_SEND_INTERVAL_P2S,
                            ServerConstants.HEARTBEAT_RECEIVE_INTERVAL_P2S }) // Heartbeat intervals in milliseconds {
                                                                              // send, receive }
                    .setTaskScheduler(heartbeatTaskScheduler()); // Scheduler for heartbeats
        } else {
            /**
             * Enables external message broker to handle subscription-based
             * messaging.
             * - Subscriptions (sending messages to users) are created with prefixes like:
             * "/user/{sessionId}/queue/.." for user-specific destinations.
             */
            config
                    .enableStompBrokerRelay("/queue") // <- destination
                    .setRelayHost(appProperties.getBrokerHost()) // <- hostname
                    .setRelayPort(appProperties.getBrokerPort()) // <- port
                    .setSystemLogin(appProperties.getBrokerUsername()) // <- system username
                    .setSystemPasscode(appProperties.getBrokerPassword()) // <- system password
                    .setClientLogin(appProperties.getBrokerUsername()) // <- client username
                    .setClientPasscode(appProperties.getBrokerPassword()); // <- client password
        }
    }

    /**
     * Restrict what authenticated clients may do over the STOMP connection.
     *
     * Without this, a client could SUBSCRIBE directly to the raw broker
     * destination (e.g. "/queue/**") and receive messages routed to other
     * users' session-scoped queues. By forcing subscriptions through the
     * "/user/**" user-destination prefix, Spring resolves each subscription to
     * the subscriber's own session, preventing cross-user access. Sends are
     * restricted to the "/app/**" application prefix so clients cannot publish
     * straight onto the broker.
     */
    @Override
    public void configureClientInboundChannel(@NonNull ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
                StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
                StompCommand command = accessor.getCommand();

                if (StompCommand.SUBSCRIBE.equals(command)) {
                    String destination = accessor.getDestination();
                    if (destination == null || !destination.startsWith("/user/")) {
                        throw new AccessDeniedException(
                                "Subscription destination not permitted");
                    }
                } else if (StompCommand.SEND.equals(command)) {
                    String destination = accessor.getDestination();
                    if (destination == null || !destination.startsWith("/app/")) {
                        throw new AccessDeniedException(
                                "Send destination not permitted");
                    }
                }

                return message;
            }
        });
    }

    @Override
    public void registerStompEndpoints(@NonNull StompEndpointRegistry registry) {
        // Clients will connect to this endpoint for WebSocket communication.
        registry.addEndpoint("/clipsocket")
                .addInterceptors(new HttpSessionHandshakeInterceptor())  // Bridge HTTP session ID to WebSocket
                .setAllowedOrigins(appProperties.getAllowedOrigins());
    }

    // Scheduler for WebSocket heartbeats
    @Bean
    public TaskScheduler heartbeatTaskScheduler() {
        ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(1); // <- default: 1
        taskScheduler.setThreadNamePrefix("WebSocketHeartbeat-");
        return taskScheduler;
    }

    // server-level WebSocket settings.
    @Bean
    public ServletServerContainerFactoryBean servletServerContainerFactoryBean() {
        int maxMessageSizeInBytes = (int) appProperties.getOverheadMaxMessageSizeInBytes();
        ServletServerContainerFactoryBean factory = new ServletServerContainerFactoryBean();

        /*
         * [RECEIVE/SEND][EACH CONNECTION]
         * Sets the maximum WebSocket message size (in bytes) allowed by the server
         * (Tomcat).
         * If a message exceeds this size, the connection is terminated without
         * displaying an error in the console, as this limit is enforced at the server
         * level.
         */
        factory.setMaxTextMessageBufferSize(maxMessageSizeInBytes); // default: 8 * 1024
        factory.setMaxBinaryMessageBufferSize(maxMessageSizeInBytes); // default : 8 * 1024

        return factory;
    }

    // STOMP WebSocket transport settings
    @Override
    public void configureWebSocketTransport(@SuppressWarnings("null") WebSocketTransportRegistration registration) {
        int maxMessageSizeInBytes = (int) appProperties.getOverheadMaxMessageSizeInBytes();

        /*
         * [RECEIVE/SEND][EACH CONNECTION]
         * Sets the maximum WebSocket message size (in bytes) allowed by the transport
         * layer.
         * If a message exceeds this size, the connection is terminated, and an error is
         * displayed in the console.
         */
        registration.setMessageSizeLimit(maxMessageSizeInBytes); // default : 64 * 1024

        /*
         * [SEND][EACH CONNECTION]
         * Sets the timeout for sending messages. If an outgoing message exceeds this
         * time while a new message arrives, the connection is terminated, and a warning
         * is logged.
         */
        registration.setSendTimeLimit(200 * 1000); // default : 10 * 1000

        /*
         * [SEND][EACH CONNECTION]
         * Configures the buffer size (in bytes) used for sending queued messages. If
         * the buffer is full and the timeout expires, the connection is terminated, and
         * a warning is logged.
         * This occurs again when a new message arrives.
         */
        registration
                .setSendBufferSizeLimit(maxMessageSizeInBytes); // default : 512 * 1024
    }

}
