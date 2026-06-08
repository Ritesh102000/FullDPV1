package com.example.moshi.ws;

import com.example.moshi.config.MoshiProperties;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

@Component
public class MoshiVoiceGatewayHandler extends BinaryWebSocketHandler {

	private static final Logger log = LoggerFactory.getLogger(MoshiVoiceGatewayHandler.class);
	private static final Duration MOSHI_CONNECT_TIMEOUT = Duration.ofSeconds(10);

	private final MoshiProperties moshiProperties;
	private final StandardWebSocketClient moshiClient = new StandardWebSocketClient();
	private final Map<String, WebSocketSession> moshiSessionsByBrowserSessionId = new ConcurrentHashMap<>();

	public MoshiVoiceGatewayHandler(MoshiProperties moshiProperties) {
		this.moshiProperties = moshiProperties;
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession browserSession) throws Exception {
		log.info("Browser WebSocket connected: {}", browserSession.getId());

		WebSocketHandler moshiHandler = new MoshiBackendHandler(browserSession);
		String moshiWebsocketUrl = moshiWebsocketUrlFor(browserSession);
		WebSocketSession moshiSession;
		try {
			moshiSession = moshiClient
					.execute(moshiHandler, moshiWebsocketUrl)
					.get(MOSHI_CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (Exception exception) {
			log.warn("Could not connect browser session {} to Moshi at {}",
					browserSession.getId(), moshiWebsocketUrl, exception);
			browserSession.close(CloseStatus.SERVER_ERROR.withReason("Could not connect to Moshi on port 8998."));
			return;
		}

		moshiSessionsByBrowserSessionId.put(browserSession.getId(), moshiSession);
		log.info("Moshi WebSocket connected for browser session {} using {}", browserSession.getId(), moshiWebsocketUrl);
	}

	@Override
	protected void handleBinaryMessage(WebSocketSession browserSession, BinaryMessage message) throws Exception {
		WebSocketSession moshiSession = moshiSessionsByBrowserSessionId.get(browserSession.getId());
		if (moshiSession == null || !moshiSession.isOpen()) {
			browserSession.close(CloseStatus.SERVER_ERROR.withReason("Moshi backend is not connected."));
			return;
		}

		moshiSession.sendMessage(new BinaryMessage(copyPayload(message)));
	}

	@Override
	protected void handleTextMessage(WebSocketSession browserSession, TextMessage message) {
		WebSocketSession moshiSession = moshiSessionsByBrowserSessionId.get(browserSession.getId());
		if (moshiSession == null || !moshiSession.isOpen()) {
			closeBrowserSession(browserSession, CloseStatus.SERVER_ERROR.withReason("Moshi backend is not connected."));
			return;
		}

		sendText(moshiSession, message.getPayload(), "Moshi backend");
	}

	@Override
	public void handleTransportError(WebSocketSession browserSession, Throwable exception) throws Exception {
		log.warn("Browser WebSocket error for session {}", browserSession.getId(), exception);
		closeMoshiSession(browserSession.getId(), CloseStatus.SERVER_ERROR);
	}

	@Override
	public void afterConnectionClosed(WebSocketSession browserSession, CloseStatus status) throws Exception {
		log.info("Browser WebSocket closed: {}, status={}", browserSession.getId(), status);
		closeMoshiSession(browserSession.getId(), status);
	}

	private void closeMoshiSession(String browserSessionId, CloseStatus status) throws IOException {
		WebSocketSession moshiSession = moshiSessionsByBrowserSessionId.remove(browserSessionId);
		if (moshiSession != null && moshiSession.isOpen()) {
			moshiSession.close(status);
		}
	}

	private static void closeBrowserSession(WebSocketSession browserSession, CloseStatus status) {
		try {
			browserSession.close(status);
		}
		catch (IOException exception) {
			log.warn("Could not close browser session {}", browserSession.getId(), exception);
		}
	}

	private static void sendText(WebSocketSession session, String payload, String sessionName) {
		try {
			session.sendMessage(new TextMessage(payload));
		}
		catch (IOException exception) {
			log.warn("Could not send text message to {}", sessionName, exception);
		}
	}

	private String moshiWebsocketUrlFor(WebSocketSession browserSession) {
		String browserQuery = browserSession.getUri() == null ? null : browserSession.getUri().getRawQuery();
		return appendQuery(moshiProperties.websocketUrl(), browserQuery);
	}

	static String appendQuery(String websocketUrl, String rawQuery) {
		if (rawQuery == null || rawQuery.isBlank()) {
			return websocketUrl;
		}

		if (websocketUrl.endsWith("?") || websocketUrl.endsWith("&")) {
			return websocketUrl + rawQuery;
		}

		return websocketUrl + (websocketUrl.contains("?") ? "&" : "?") + rawQuery;
	}

	private static byte[] copyPayload(WebSocketMessage<?> message) {
		ByteBuffer payload = ((ByteBuffer) message.getPayload()).asReadOnlyBuffer();
		byte[] bytes = new byte[payload.remaining()];
		payload.get(bytes);
		return bytes;
	}

	private final class MoshiBackendHandler extends BinaryWebSocketHandler {

		private final WebSocketSession browserSession;

		private MoshiBackendHandler(WebSocketSession browserSession) {
			this.browserSession = browserSession;
		}

		@Override
		public void afterConnectionEstablished(WebSocketSession moshiSession) {
			log.info("Backend Moshi session established: {}", moshiSession.getId());
		}

		@Override
		protected void handleBinaryMessage(WebSocketSession moshiSession, BinaryMessage message) throws Exception {
			if (browserSession.isOpen()) {
				browserSession.sendMessage(new BinaryMessage(copyPayload(message)));
			}
		}

		@Override
		protected void handleTextMessage(WebSocketSession moshiSession, TextMessage message) {
			if (browserSession.isOpen()) {
				sendText(browserSession, message.getPayload(), "browser");
			}
		}

		@Override
		public void handleTransportError(WebSocketSession moshiSession, Throwable exception) throws Exception {
			log.warn("Moshi WebSocket error for browser session {}", browserSession.getId(), exception);
			if (browserSession.isOpen()) {
				browserSession.close(CloseStatus.SERVER_ERROR.withReason("Moshi backend error."));
			}
		}

		@Override
		public void afterConnectionClosed(WebSocketSession moshiSession, CloseStatus status) throws Exception {
			log.info("Moshi WebSocket closed for browser session {}, status={}", browserSession.getId(), status);
			moshiSessionsByBrowserSessionId.remove(browserSession.getId());
			if (browserSession.isOpen()) {
				browserSession.close(status);
			}
		}
	}
}
