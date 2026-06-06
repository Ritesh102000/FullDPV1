package com.example.moshi.api;

import com.example.moshi.config.MoshiProperties;
import com.example.moshi.runtime.MoshiProcessManager;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@RestController
@RequestMapping("/api/moshi")
public class MoshiGatewayController {

	private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(5);

	private final MoshiProperties moshiProperties;
	private final MoshiProcessManager moshiProcessManager;
	private final StandardWebSocketClient webSocketClient = new StandardWebSocketClient();

	public MoshiGatewayController(MoshiProperties moshiProperties, MoshiProcessManager moshiProcessManager) {
		this.moshiProperties = moshiProperties;
		this.moshiProcessManager = moshiProcessManager;
	}

	@GetMapping("/status")
	public StatusResponse status() {
		return new StatusResponse(
				moshiProperties.websocketUrl(),
				"/api/chat",
				"configured",
				moshiProcessManager.snapshot()
		);
	}

	@PostMapping("/start")
	public MoshiProcessManager.Snapshot startMoshi() {
		moshiProcessManager.startIfNeeded();
		return moshiProcessManager.snapshot();
	}

	@PostMapping("/check")
	public ResponseEntity<CheckResponse> checkMoshiConnection() {
		WebSocketHandler handler = new TextWebSocketHandler();

		try {
			WebSocketSession session = webSocketClient
					.execute(handler, moshiProperties.websocketUrl())
					.get(CHECK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

			if (session.isOpen()) {
				session.close(CloseStatus.NORMAL);
			}

			return ResponseEntity.ok(new CheckResponse(true, "Connected to Moshi WebSocket."));
		}
		catch (Exception exception) {
			return ResponseEntity.internalServerError()
					.body(new CheckResponse(false, "Could not connect to Moshi: " + exception.getMessage()));
		}
	}

	public record StatusResponse(
			String moshiWebsocketUrl,
			String voiceGatewayPath,
			String status,
			MoshiProcessManager.Snapshot moshiProcess
	) {
	}

	public record CheckResponse(boolean ok, String message) {
	}
}
