# Moshi Spring Gateway Project Notes

## What This Project Does

This is a local learning/demo project that lets a browser talk to Kyutai Moshi through a Spring Boot gateway.

Current architecture:

```text
Browser
  -> http://localhost:8080
  -> ws://localhost:8080/api/chat
  -> Spring Boot gateway
  -> ws://localhost:8998/api/chat
  -> Moshi MLX running locally on macOS
```

Important behavior:

- Spring serves the browser UI.
- The browser connects only to Spring.
- Spring relays Moshi-compatible WebSocket binary frames to the local Moshi MLX server.
- Spring auto-starts Moshi when the Spring app starts, if Moshi is not already listening on port `8998`.
- Spring auto-installs `moshi_mlx` into `~/.venvs/moshi-mlx` if that venv/module is missing.
- Moshi model weights are cached by Hugging Face under `~/.cache/huggingface`, so they should not fully download on every start.

## How To Run

One-time setup on a new machine:

```bash
brew install python@3.12
```

Spring will create `~/.venvs/moshi-mlx` and install `moshi_mlx` on first run if needed.

Start the Spring app:

```bash
cd /Users/riteshrajput/Desktop/FullDPV1/moshi-spring-gateway
./mvnw spring-boot:run
```

Open the voice UI:

```text
http://localhost:8080
```

Then:

1. Wait for Moshi to become ready.
2. Click `Connect`.
3. Allow microphone permission.
4. Talk to Moshi.

Debug/status page:

```text
http://localhost:8080/gateway.html
```

Status API:

```bash
curl http://localhost:8080/api/moshi/status
```

Moshi is ready when the status contains:

```json
"state":"ready"
```

## How To Stop

Find and stop Spring and Moshi:

```bash
lsof -tiTCP:8080 -sTCP:LISTEN
lsof -tiTCP:8998 -sTCP:LISTEN
pgrep -af 'moshi_mlx.local_web'
```

Then kill the returned process IDs:

```bash
kill <pid>
```

Use `kill -9 <pid>` only if normal `kill` does not stop the process.

## Moshi Setup

Moshi MLX is installed in this virtual environment:

```text
~/.venvs/moshi-mlx
```

The configured Python executable is:

```text
~/.venvs/moshi-mlx/bin/python
```

Manual Moshi start command:

```bash
source ~/.venvs/moshi-mlx/bin/activate
python -m moshi_mlx.local_web -q 4 --host 0.0.0.0 --port 8998 --no-browser
```

Spring runs that same command automatically by default.

## Spring Configuration

Main config file:

```text
src/main/resources/application.properties
```

Current properties:

```properties
spring.application.name=moshi-spring-gateway
moshi.websocket-url=${MOSHI_WS_URL:ws://localhost:8998/api/chat}
moshi.auto-start=${MOSHI_AUTO_START:true}
moshi.auto-install=${MOSHI_AUTO_INSTALL:true}
moshi.python-executable=${MOSHI_PYTHON:${user.home}/.venvs/moshi-mlx/bin/python}
moshi.bootstrap-python=${MOSHI_BOOTSTRAP_PYTHON:/opt/homebrew/bin/python3.12}
moshi.host=${MOSHI_HOST:0.0.0.0}
moshi.port=${MOSHI_PORT:8998}
moshi.quantized=${MOSHI_QUANTIZED:4}
```

Repo-friendly config templates:

```text
.env.example
config/application.example.properties
```

Option A, use environment variables:

```bash
cp .env.example .env
set -a
source .env
set +a
./mvnw spring-boot:run
```

Option B, use a local Spring profile file:

```bash
cp config/application.example.properties config/application-local.properties
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Local-only files are ignored by git:

```text
.env
config/application-local.properties
```

Useful overrides:

```bash
MOSHI_AUTO_START=false ./mvnw spring-boot:run
MOSHI_AUTO_INSTALL=false ./mvnw spring-boot:run
MOSHI_BOOTSTRAP_PYTHON=/path/to/python3.12 ./mvnw spring-boot:run
MOSHI_PYTHON=/path/to/python ./mvnw spring-boot:run
MOSHI_WS_URL=ws://localhost:8998/api/chat ./mvnw spring-boot:run
```

Tests disable Moshi auto-start using:

```text
src/test/resources/application.properties
```

## Files And Responsibilities

### `pom.xml`

Maven project definition generated from Spring Initializr.

Important dependencies:

- `spring-boot-starter-webmvc`: serves REST endpoints and static files.
- `spring-boot-starter-websocket`: provides WebSocket server/client support.
- `spring-boot-starter-validation`: validates configuration properties.

### `src/main/java/com/example/moshi/MoshiSpringGatewayApplication.java`

Main Spring Boot entrypoint.

Also enables configuration property scanning with:

```java
@ConfigurationPropertiesScan
```

That allows `MoshiProperties` to bind values from `application.properties`.

### `src/main/java/com/example/moshi/config/MoshiProperties.java`

Typed config object for all `moshi.*` settings.

It stores:

- `websocketUrl`: backend Moshi WebSocket URL.
- `autoStart`: whether Spring should start Moshi automatically.
- `autoInstall`: whether Spring should create the venv and install `moshi_mlx` automatically.
- `pythonExecutable`: Python executable used to run `moshi_mlx`.
- `bootstrapPython`: Python executable used to create the venv when `pythonExecutable` does not exist.
- `host`: host passed to Moshi.
- `port`: port passed to Moshi.
- `quantized`: q4/q8 MLX model choice.

It also provides safe defaults, so tests can disable auto-start without redefining every property.

### `src/main/java/com/example/moshi/runtime/MoshiProcessManager.java`

Local development process manager for Moshi.

Responsibilities:

- Runs at Spring startup.
- Checks if Moshi is already listening on the configured port.
- Checks if `moshi_mlx.local_web` is installed for the configured Python.
- Creates the configured Python venv and installs `moshi_mlx` if auto-install is enabled.
- Starts Moshi if needed.
- Captures recent Moshi logs.
- Exposes current state through a `Snapshot`.
- Stops the child Moshi process when Spring stops.

States:

- `not-started`
- `disabled`
- `not-installed`
- `installing`
- `starting`
- `ready`
- `error`

This is intentionally simple local-dev orchestration, not production process supervision.

### `src/main/java/com/example/moshi/api/MoshiGatewayController.java`

REST controller under:

```text
/api/moshi
```

Endpoints:

- `GET /api/moshi/status`
  - Returns gateway config and Moshi process state.
- `POST /api/moshi/start`
  - Tries to start Moshi if it is not already running.
- `POST /api/moshi/check`
  - Opens and closes a WebSocket connection to Moshi to verify connectivity.

### `src/main/java/com/example/moshi/ws/WebSocketConfig.java`

Registers Spring WebSocket endpoints:

```text
/ws/voice
/api/chat
```

Both use `MoshiVoiceGatewayHandler`.

`/api/chat` exists because Kyutai's browser client expects to connect to:

```text
ws://<same-host>/api/chat
```

### `src/main/java/com/example/moshi/ws/MoshiVoiceGatewayHandler.java`

The WebSocket relay between browser and Moshi.

For each browser WebSocket session:

1. Opens one backend WebSocket session to Moshi.
2. Relays browser binary frames to Moshi.
3. Relays Moshi binary frames back to the browser.
4. Relays text frames if any appear.
5. Closes the paired Moshi session when the browser disconnects.

Moshi's browser protocol is preserved as-is:

- `0x00`: handshake/ready
- `0x01 + Opus bytes`: audio
- `0x02 + UTF-8 bytes`: text token

Spring does not decode or transform the audio.

### `src/main/resources/static/index.html`

The main browser voice UI.

This is Kyutai's Moshi web client from the cached Moshi artifacts bundle. It handles:

- microphone permission
- audio capture
- Opus encoding
- WebSocket streaming
- audio playback
- Moshi text display

It connects to Spring at:

```text
ws://localhost:8080/api/chat
```

Because Spring serves this file, the browser does not directly connect to Moshi.

### `src/main/resources/static/assets/*`

Static assets copied from Kyutai's Moshi web client bundle:

- bundled JavaScript app
- CSS
- audio worklet
- Opus encoder/decoder workers
- WASM decoder file
- images/logo files

These are required for the real browser voice UI.

### `src/main/resources/static/gateway.html`

Local debug/status page created before the real Moshi UI was added.

Use it to inspect:

- configured Moshi URL
- Spring gateway path
- Moshi process state
- recent Moshi logs
- manual `Check Moshi`
- manual `Start Moshi`

URL:

```text
http://localhost:8080/gateway.html
```

### `src/test/java/com/example/moshi/MoshiSpringGatewayApplicationTests.java`

Generated Spring Boot context-load test.

It verifies that the application context can start.

### `src/test/resources/application.properties`

Test-only config:

```properties
moshi.auto-start=false
```

This prevents unit tests from launching the heavy Moshi model process.

### `mvnw`, `mvnw.cmd`

Maven wrapper scripts.

Use `./mvnw` on macOS/Linux.

### `HELP.md`

Generated Spring Initializr help file.

## Important Runtime Ports

Spring:

```text
8080
```

Moshi MLX:

```text
8998
```

Check them:

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
lsof -nP -iTCP:8998 -sTCP:LISTEN
```

## Common Issues

### `Could not connect to Moshi`

Usually means Moshi is not listening on `8998` yet.

Check:

```bash
curl http://localhost:8080/api/moshi/status
lsof -nP -iTCP:8998 -sTCP:LISTEN
```

If state is `starting`, wait for model load to finish.

### `[Warn] Cannot download config, using defaults.`

This warning is not necessarily fatal.

Moshi could not fetch optional config metadata and is using defaults. It can still load and serve if the model files are available.

### Model Downloads

Model files are large. First run can take several minutes.

Cache location:

```text
~/.cache/huggingface
```

The q4 model cache seen on this machine:

```text
~/.cache/huggingface/hub/models--kyutai--moshiko-mlx-q4
```

### Browser Microphone

Microphone access works on `localhost`. If served from a remote host later, HTTPS may be required.

### Do Not Use `localhost:8998` For The Spring Demo

`http://localhost:8998` is Moshi's built-in UI and bypasses Spring.

For the Spring gateway demo, use:

```text
http://localhost:8080
```

## Verified Commands

Clean test:

```bash
./mvnw clean test
```

Manual WebSocket handshake test:

```bash
node -e "const ws=new WebSocket('ws://localhost:8080/api/chat'); ws.binaryType='arraybuffer'; ws.onopen=()=>console.log('open'); ws.onmessage=(e)=>{const b=new Uint8Array(e.data); console.log('message', b.length, b[0]); ws.close(); process.exit(b[0]===0?0:2)}; ws.onerror=()=>process.exit(1); setTimeout(()=>process.exit(3),10000);"
```

Expected:

```text
open
message 1 0
```

## Current Development Assumptions

- macOS Apple Silicon.
- Moshi runs locally through MLX, not Docker.
- Spring Boot is the browser-facing gateway.
- Single local Moshi instance.
- This is a learning/demo setup, not production-ready orchestration.
