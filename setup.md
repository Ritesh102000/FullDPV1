# Moshi Spring Gateway Setup

This repo contains a local demo where a browser talks to Kyutai Moshi through a Spring Boot gateway.

Current flow:

```text
Browser
  -> http://localhost:8080
  -> ws://localhost:8080/api/chat
  -> Spring Boot gateway
  -> ws://localhost:8998/api/chat
  -> Moshi MLX running locally
```

Spring serves the web UI, exposes status endpoints, starts Moshi MLX when needed, and relays Moshi WebSocket frames between the browser and Moshi.

## Important Scope

This setup is for local macOS development with Moshi MLX.

- Use Apple Silicon macOS for the intended MLX path.
- Moshi model files are large. First startup can take several minutes.
- Model files are cached by Hugging Face under `~/.cache/huggingface`.
- The browser must connect to Spring, not directly to Moshi.

## Prerequisites

Install these before running the project:

- Git
- Java 21
- Homebrew
- Python 3.12

Check Java:

```bash
java -version
```

If Java 21 is missing, install it with Homebrew:

```bash
brew install openjdk@21
```

If your shell does not find Java 21 after installation, add it to your path:

```bash
echo 'export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
java -version
```

Install Python 3.12:

```bash
brew install python@3.12
```

## Clone The Repo

```bash
git clone https://github.com/Ritesh102000/FullDPV1.git
cd FullDPV1/moshi-spring-gateway
```

## Install Moshi MLX

Create a dedicated Python virtual environment:

```bash
/opt/homebrew/bin/python3.12 -m venv ~/.venvs/moshi-mlx
```

Install Moshi MLX:

```bash
~/.venvs/moshi-mlx/bin/python -m pip install -U pip moshi_mlx
```

Verify Moshi MLX is importable:

```bash
~/.venvs/moshi-mlx/bin/python -m moshi_mlx.local_web --help
```

If that command prints help text, Moshi MLX is installed correctly.

## Configuration

The default config is in:

```text
moshi-spring-gateway/src/main/resources/application.properties
```

Default values:

```properties
moshi.websocket-url=${MOSHI_WS_URL:ws://localhost:8998/api/chat}
moshi.auto-start=${MOSHI_AUTO_START:true}
moshi.python-executable=${MOSHI_PYTHON:${user.home}/.venvs/moshi-mlx/bin/python}
moshi.host=${MOSHI_HOST:0.0.0.0}
moshi.port=${MOSHI_PORT:8998}
moshi.quantized=${MOSHI_QUANTIZED:4}
```

For most local runs, you do not need to change anything.

Optional environment config:

```bash
cp .env.example .env
set -a
source .env
set +a
```

Optional Spring local profile config:

```bash
cp config/application.example.properties config/application-local.properties
```

Then run with:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Local config files are ignored by git:

```text
.env
config/application-local.properties
```

## Start Everything

From the Spring project directory:

```bash
cd FullDPV1/moshi-spring-gateway
./mvnw spring-boot:run
```

Spring will:

1. Start on port `8080`.
2. Check whether Moshi is already listening on port `8998`.
3. Start Moshi MLX automatically if needed.
4. Serve the browser UI.

Open:

```text
http://localhost:8080
```

Then:

1. Wait until Moshi is ready.
2. Click `Connect`.
3. Allow microphone access.
4. Talk to Moshi.

## First Startup

The first run can look slow because Moshi downloads model files.

That is expected. Let it finish.

The downloads should be reused on later starts from:

```text
~/.cache/huggingface
```

If a download times out, Moshi may resume automatically on the next start.

## Status And Debugging

Open the debug page:

```text
http://localhost:8080/gateway.html
```

Check Moshi status with curl:

```bash
curl http://localhost:8080/api/moshi/status
```

Moshi is ready when the response contains:

```json
"state":"ready"
```

Run a connection check:

```bash
curl -X POST http://localhost:8080/api/moshi/check
```

Useful log lines:

```text
[moshi]
Moshi backend connection opened
Moshi backend connection closed
```

## Stop Everything

If Spring is running in the terminal, press:

```text
Ctrl+C
```

If ports are still busy, find the process IDs:

```bash
lsof -tiTCP:8080 -sTCP:LISTEN
lsof -tiTCP:8998 -sTCP:LISTEN
pgrep -af 'moshi_mlx.local_web'
```

Stop a process:

```bash
kill <pid>
```

Use this only if normal `kill` does not work:

```bash
kill -9 <pid>
```

## Run Tests

From `moshi-spring-gateway`:

```bash
./mvnw test
```

Tests disable Moshi auto-start using:

```text
src/test/resources/application.properties
```

## Common Problems

### `Moshi is not installed`

Spring could not run the configured Python module.

Fix:

```bash
~/.venvs/moshi-mlx/bin/python -m pip install -U moshi_mlx
```

Then restart Spring.

### `Connection to ws://localhost:8998/api/chat failed`

Moshi is not ready yet, crashed, or is running on another port.

Check:

```bash
curl http://localhost:8080/api/moshi/status
lsof -iTCP:8998 -sTCP:LISTEN
```

### Browser microphone does not work

Use:

```text
http://localhost:8080
```

For local development, browsers usually allow microphone access on `localhost`. If you use another hostname, the browser may require HTTPS.

### Port `8080` is already in use

Find the process:

```bash
lsof -iTCP:8080 -sTCP:LISTEN
```

Stop it or run Spring on another port:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
```

### Port `8998` is already in use

Another Moshi process may already be running.

Find it:

```bash
lsof -iTCP:8998 -sTCP:LISTEN
pgrep -af 'moshi_mlx.local_web'
```

Stop it if needed:

```bash
kill <pid>
```

## Manual Moshi Start

Spring starts Moshi automatically by default. If you want to start Moshi yourself:

```bash
source ~/.venvs/moshi-mlx/bin/activate
python -m moshi_mlx.local_web -q 4 --host 0.0.0.0 --port 8998 --no-browser
```

Then start Spring with auto-start disabled:

```bash
MOSHI_AUTO_START=false ./mvnw spring-boot:run
```

## Project Layout

```text
FullDPV1/
  setup.md
  moshi-spring-gateway/
    pom.xml
    .env.example
    config/application.example.properties
    src/main/java/com/example/moshi/
      MoshiSpringGatewayApplication.java
      api/MoshiGatewayController.java
      config/MoshiProperties.java
      runtime/MoshiProcessManager.java
      ws/WebSocketConfig.java
      ws/MoshiVoiceGatewayHandler.java
    src/main/resources/static/
      index.html
      gateway.html
      assets/
```

## Useful URLs

- Main voice UI: `http://localhost:8080`
- Debug page: `http://localhost:8080/gateway.html`
- Status API: `http://localhost:8080/api/moshi/status`
- Moshi backend WebSocket: `ws://localhost:8998/api/chat`
- Spring gateway WebSocket: `ws://localhost:8080/api/chat`
