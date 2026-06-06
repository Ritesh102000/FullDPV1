package com.example.moshi.runtime;

import com.example.moshi.config.MoshiProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
	private static final Duration VENV_TIMEOUT = Duration.ofMinutes(5);
	private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(20);
	private static final int MAX_LOG_LINES = 80;

	private final MoshiProperties properties;
	private final ExecutorService logReader = Executors.newSingleThreadExecutor();
	private final Object monitor = new Object();
	private final ArrayDeque<String> recentLogs = new ArrayDeque<>();

	private volatile Process process;
	private volatile Process setupProcess;
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
			if (!installMoshiIfEnabled()) {
				state = State.NOT_INSTALLED;
				if (!properties.autoInstall()) {
					message = "moshi_mlx is not installed for " + properties.pythonExecutable() + ".";
				}
				log.warn(message);
				return;
			}
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
				properties.autoInstall(),
				currentState.name().toLowerCase().replace('_', '-'),
				message,
				properties.pythonExecutable(),
				properties.bootstrapPython(),
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

	private boolean installMoshiIfEnabled() {
		if (!properties.autoInstall()) {
			return false;
		}

		state = State.INSTALLING;
		Path pythonPath = Path.of(properties.pythonExecutable()).toAbsolutePath();
		Path venvPath = venvPathFor(pythonPath);

		if (!Files.exists(pythonPath)) {
			if (venvPath == null) {
				message = "Could not infer a virtual environment path from " + pythonPath + ".";
				log.warn(message);
				return false;
			}

			try {
				Path venvParent = venvPath.getParent();
				if (venvParent != null) {
					Files.createDirectories(venvParent);
				}
			}
			catch (IOException exception) {
				message = "Could not create parent directory for " + venvPath + ": " + exception.getMessage();
				log.warn(message, exception);
				return false;
			}

			message = "Creating Moshi Python virtual environment at " + venvPath + ".";
			log.info(message);
			if (!runCommand(
					List.of(properties.bootstrapPython(), "-m", "venv", venvPath.toString()),
					VENV_TIMEOUT,
					"[moshi setup]"
			)) {
				message = "Could not create Moshi Python virtual environment. Install Python 3.12 or set MOSHI_BOOTSTRAP_PYTHON.";
				log.warn(message);
				return false;
			}
		}

		message = "Installing moshi_mlx into " + pythonPath + ". This can take a few minutes.";
		log.info(message);
		if (!runCommand(
				List.of(pythonPath.toString(), "-m", "pip", "install", "-U", "pip", "moshi_mlx"),
				INSTALL_TIMEOUT,
				"[moshi setup]"
		)) {
			message = "Could not install moshi_mlx. Check the recent Moshi setup logs.";
			log.warn(message);
			return false;
		}

		if (!isMoshiInstalled()) {
			message = "moshi_mlx install command finished, but the module still could not be imported.";
			log.warn(message);
			return false;
		}

		message = "moshi_mlx installed successfully.";
		log.info(message);
		return true;
	}

	private Path venvPathFor(Path pythonPath) {
		Path parent = pythonPath.getParent();
		if (parent == null) {
			return null;
		}

		if ("bin".equals(parent.getFileName().toString())) {
			return parent.getParent();
		}

		return parent;
	}

	private boolean runCommand(List<String> command, Duration timeout, String logPrefix) {
		Process commandProcess;
		try {
			commandProcess = new ProcessBuilder(command)
					.redirectErrorStream(true)
					.start();
		}
		catch (IOException exception) {
			addLogLine(logPrefix + " " + exception.getMessage());
			log.warn("Could not run command: {}", String.join(" ", command), exception);
			return false;
		}

		setupProcess = commandProcess;
		Future<?> outputReader = logReader.submit(() -> readCommandLogs(commandProcess, logPrefix));

		try {
			boolean completed = commandProcess.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
			if (!completed) {
				commandProcess.destroyForcibly();
				outputReader.cancel(true);
				addLogLine(logPrefix + " timed out: " + String.join(" ", command));
				return false;
			}

			try {
				outputReader.get(2, TimeUnit.SECONDS);
			}
			catch (TimeoutException exception) {
				outputReader.cancel(true);
			}

			return commandProcess.exitValue() == 0;
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			commandProcess.destroyForcibly();
			return false;
		}
		catch (Exception exception) {
			log.warn("Command failed: {}", String.join(" ", command), exception);
			return false;
		}
		finally {
			if (setupProcess == commandProcess) {
				setupProcess = null;
			}
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

	private void readCommandLogs(Process commandProcess, String logPrefix) {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(commandProcess.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				String setupLogLine = logPrefix + " " + line;
				addLogLine(setupLogLine);
				log.info("{}", setupLogLine);
			}
		}
		catch (IOException exception) {
			addLogLine(logPrefix + " Could not read command logs: " + exception.getMessage());
			log.warn("Could not read command logs", exception);
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
		Process currentSetupProcess = setupProcess;
		if (currentSetupProcess != null && currentSetupProcess.isAlive()) {
			log.info("Stopping Moshi setup process started by Spring.");
			currentSetupProcess.destroy();
		}

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
		INSTALLING,
		STARTING,
		READY,
		ERROR
	}

	public record Snapshot(
			boolean autoStart,
			boolean autoInstall,
			String state,
			String message,
			String pythonExecutable,
			String bootstrapPython,
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
