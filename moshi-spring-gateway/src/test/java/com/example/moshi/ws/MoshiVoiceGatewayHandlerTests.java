package com.example.moshi.ws;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MoshiVoiceGatewayHandlerTests {

	@Test
	void appendQueryKeepsPlainBackendUrlUnchangedWhenBrowserHasNoQuery() {
		assertThat(MoshiVoiceGatewayHandler.appendQuery("ws://localhost:8998/api/chat", null))
				.isEqualTo("ws://localhost:8998/api/chat");
	}

	@Test
	void appendQueryForwardsBrowserMoshiParamsToBackend() {
		String backendUrl = MoshiVoiceGatewayHandler.appendQuery(
				"ws://localhost:8998/api/chat",
				"text_temperature=0.7&audio_temperature=0.8&pad_mult=2"
		);

		assertThat(backendUrl)
				.isEqualTo("ws://localhost:8998/api/chat?text_temperature=0.7&audio_temperature=0.8&pad_mult=2");
	}

	@Test
	void appendQueryPreservesExistingBackendQuery() {
		String backendUrl = MoshiVoiceGatewayHandler.appendQuery(
				"ws://localhost:8998/api/chat?worker_auth_id=local",
				"text_temperature=0.7"
		);

		assertThat(backendUrl)
				.isEqualTo("ws://localhost:8998/api/chat?worker_auth_id=local&text_temperature=0.7");
	}
}
