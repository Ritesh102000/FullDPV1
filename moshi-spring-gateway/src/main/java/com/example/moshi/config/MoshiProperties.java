package com.example.moshi.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "moshi")
public record MoshiProperties(
		@NotBlank String websocketUrl,
		boolean autoStart,
		boolean autoInstall,
		@NotBlank String pythonExecutable,
		@NotBlank String bootstrapPython,
		@NotBlank String host,
		@Min(1) @Max(65535) int port,
		@Min(4) @Max(8) int quantized
) {

	public MoshiProperties {
		websocketUrl = defaultIfBlank(websocketUrl, "ws://localhost:8998/api/chat");
		pythonExecutable = defaultIfBlank(
				pythonExecutable,
				System.getProperty("user.home") + "/.venvs/moshi-mlx/bin/python"
		);
		bootstrapPython = defaultIfBlank(bootstrapPython, "/opt/homebrew/bin/python3.12");
		host = defaultIfBlank(host, "0.0.0.0");
		port = port == 0 ? 8998 : port;
		quantized = quantized == 0 ? 4 : quantized;
	}

	private static String defaultIfBlank(String value, String defaultValue) {
		return value == null || value.isBlank() ? defaultValue : value;
	}
}
