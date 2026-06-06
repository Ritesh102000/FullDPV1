package com.example.moshi.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

	private final MoshiVoiceGatewayHandler moshiVoiceGatewayHandler;

	public WebSocketConfig(MoshiVoiceGatewayHandler moshiVoiceGatewayHandler) {
		this.moshiVoiceGatewayHandler = moshiVoiceGatewayHandler;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(moshiVoiceGatewayHandler, "/ws/voice", "/api/chat")
				.setAllowedOrigins("http://localhost:8080", "http://127.0.0.1:8080");
	}
}
