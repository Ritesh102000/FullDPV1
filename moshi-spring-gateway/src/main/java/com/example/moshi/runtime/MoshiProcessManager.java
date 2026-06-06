package com.example.moshi.runtime;

import com.example.moshi.config.MoshiProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class MoshiProcessManager implements ApplicationRunner, SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(MoshiProcessManager.class);
	private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(8);
	private static final int MAX_LOG_LINES = 80;

	private final MoshiProperties properties;
	private final ExecutorService logReader = Executors.newSingleThreadExecutor();
	private final Object monitor = new Object();
	private final ArrayDeque<String> recentLogs = new ArrayDeque<>();

	private volatile Process process;
	private volatile State state = State.NOT_STARTED;
	private volatile String message = "Moshi has not been checked yet.";
	private volatile Instant startedAt;
	private volatile boolean lifecycleRunning;

	public MoshiProcessManager(MoshiProperties properties) {
		this.properties = properties;
	}

	@Override
	public void run(ApplicationArguments args) {
		startIfNeeded();
	}

	public synchronized void startIfNeeded() {
		if (!properties.autoStart()) {
			state = State.DISABLED;
			message = "Moshi auto-start is disabled.";
			log.info(message);
			return;
		}

		if (isPortOpen()) {
			state = State.READY;
			message = "Moshi is already listening on port " + properties.port() + ".";
			log.info(message);
			return;
		}

		if (!isMoshiInstalled()) {
			state = State.NOT_INSTALLED;
			message = "moshi_mlx is not installed for " + properties.pythonExecutable() + ".";
			log.warn(message);
			return;
		}

		if (process != null && process.isAlive()) {
			state = State.STARTING;
			message = "Moshi process is already starting.";
			return;
		}

		List<String> command = List.of(
				properties.pythonExecutable(),
				"-m", "moshi_mlx.local_web",
				"-q", Integer.toString(properties.quantized()),
				"--host", properties.host(),
				"--port", Integer.toString(properties.port()),
				"--no-browser"
		);

		try {
			process = new ProcessBuilder(command)
					.redirectErrorStream(true)
					.start();
			startedAt = Instant.now();
			state = State.STARTING;
			message = "Started Moshi process. Model download/load may take several minutes.";
			log.info("{} Command: {}", message, String.join(" ", command));
			logReader.submit(() -> readProcessLogs(process));
		}
		catch (IOException exception) {
			state = State.ERROR;
			message = "Could not start Moshi: " + exception.getMessage();
			log.warn(message, exception);
		}
	}

	public Snapshot snapshot() {
		State currentState = currentState();
		return new Snapshot(
				properties.autoStart(),
				currentState.name().toLowerCase().replace('_', '-'),
				message,
				properties.pythonExecutable(),
				properties.host(),
				properties.port(),
				properties.quantized(),
				isProcessAlive(),
				isPortOpen(),
				startedAt,
				recentLogs()
		);
	}

	private State currentState() {
		if (isPortOpen()) {
			state = State.READY;
			message = "Moshi is listening on port " + properties.port() + ".";
			return State.READY;
		}

		Process currentProcess = process;
		if (currentProcess != null && currentProcess.isAlive()) {
			state = State.STARTING;
			return State.STARTING;
		}

		if (state == State.STARTING && currentProcess != null) {
			state = State.ERROR;
			message = "Moshi process exited before opening port " + properties.port() + ".";
		}

		return state;
	}

	private boolean isMoshiInstalled() {
		Process checkProcess;
		try {
			checkProcess = new ProcessBuilder(
					properties.pythonExecutable(),
					"-c",
					"import importlib.util; raise SystemExit(0 if importlib.util.find_spec('moshi_mlx.local_web') else 1)"
			).redirectErrorStream(true).start();
		}
		catch (IOException exception) {
			message = "Could not run Python executable " + properties.pythonExecutable() + ": " + exception.getMessage();
			return false;
		}

		try {
			boolean completed = checkProcess.waitFor(COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
			if (!completed) {
				checkProcess.destroyForcibly();
				message = "Timed out checking moshi_mlx install.";
				return false;
			}
			return checkProcess.exitValue() == 0;
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			message = "Interrupted while checking moshi_mlx install.";
			return false;
		}
	}

	private boolean isPortOpen() {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("127.0.0.1", properties.port()), 500);
			return true;
		}
		catch (IOException exception) {
			return false;
		}
	}

	private boolean isProcessAlive() {
		Process currentProcess = process;
		return currentProcess != null && currentProcess.isAlive();
	}

	private void readProcessLogs(Process moshiProcess) {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(moshiProcess.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				addLogLine(line);
				log.info("[moshi] {}", line);
			}
		}
		catch (IOException exception) {
			addLogLine("Could not read Moshi logs: " + exception.getMessage());
			log.warn("Could not read Moshi logs", exception);
		}
	}

	private void addLogLine(String line) {
		synchronized (monitor) {
			recentLogs.addLast(line);
			while (recentLogs.size() > MAX_LOG_LINES) {
				recentLogs.removeFirst();
			}
		}
	}

	private List<String> recentLogs() {
		synchronized (monitor) {
			return new ArrayList<>(recentLogs);
		}
	}

	@Override
	public void start() {
		lifecycleRunning = true;
	}

	@Override
	public void stop() {
		Process currentProcess = process;
		if (currentProcess != null && currentProcess.isAlive()) {
			log.info("Stopping Moshi process started by Spring.");
			currentProcess.destroy();
		}
		logReader.shutdownNow();
		lifecycleRunning = false;
	}

	@Override
	public boolean isRunning() {
		return lifecycleRunning;
	}

	public enum State {
		NOT_STARTED,
		DISABLED,
		NOT_INSTALLED,
		STARTING,
		READY,
		ERROR
	}

	public record Snapshot(
			boolean autoStart,
			String state,
			String message,
			String pythonExecutable,
			String host,
			int port,
			int quantized,
			boolean processAlive,
			boolean portOpen,
			Instant startedAt,
			List<String> recentLogs
	) {
	}
}
