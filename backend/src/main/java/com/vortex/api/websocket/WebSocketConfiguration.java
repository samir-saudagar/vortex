package com.vortex.api.websocket;

import com.vortex.api.LocalDevelopmentOrigins;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfiguration implements WebSocketMessageBrokerConfigurer {
    private final String[] origins;
    private final org.springframework.scheduling.TaskScheduler scheduler;
    public WebSocketConfiguration(@Value("${VORTEX_STOMP_ORIGIN:}") String additionalOrigins, @org.springframework.beans.factory.annotation.Qualifier("vortexTaskScheduler") org.springframework.scheduling.TaskScheduler scheduler) { this.origins = LocalDevelopmentOrigins.including(additionalOrigins); this.scheduler = scheduler; }
    @Override public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins(origins);
    }
    @Override public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic").setTaskScheduler(scheduler).setHeartbeatValue(new long[] {10000, 10000});
        registry.setApplicationDestinationPrefixes("/app");
    }
    @Override public void configureClientInboundChannel(org.springframework.messaging.simp.config.ChannelRegistration registration) {
        // This is a read-only feed. Browsers cannot inject SEND frames into broker topics.
        registration.interceptors(new org.springframework.messaging.support.ChannelInterceptor() {
            @Override public org.springframework.messaging.Message<?> preSend(org.springframework.messaging.Message<?> message,
                    org.springframework.messaging.MessageChannel channel) {
                var headers = org.springframework.messaging.support.MessageHeaderAccessor.getAccessor(message,
                        org.springframework.messaging.simp.stomp.StompHeaderAccessor.class);
                if (headers != null && headers.getCommand() == org.springframework.messaging.simp.stomp.StompCommand.SEND)
                    throw new IllegalArgumentException("Client publication is not supported");
                return message;
            }
        });
    }
}
