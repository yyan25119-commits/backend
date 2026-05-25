package com.nailglow.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final AdminLiveWebSocketHandler adminLiveWebSocketHandler;

    public WebSocketConfig(AdminLiveWebSocketHandler adminLiveWebSocketHandler) {
        this.adminLiveWebSocketHandler = adminLiveWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(adminLiveWebSocketHandler, "/ws/admin/live")
                .setAllowedOriginPatterns("*");
    }
}
