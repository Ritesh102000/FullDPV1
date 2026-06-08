# Local Commands

Run commands from the repo root unless a command says otherwise:

```bash
cd /Users/riteshrajput/Desktop/FullDPV1
```

## Start Everything

Start Spring. Spring will auto-install Moshi MLX if needed and auto-start Moshi on port `8998`.

```bash
cd /Users/riteshrajput/Desktop/FullDPV1/moshi-spring-gateway
./mvnw spring-boot:run
```

Open the UI:

```text
http://localhost:8080
```

Open the debug page:

```text
http://localhost:8080/gateway.html
```

## Check Servers

Check Spring:

```bash
curl http://localhost:8080/api/moshi/status
```

Check if Spring is listening on `8080`:

```bash
lsof -iTCP:8080 -sTCP:LISTEN
```

Check if Moshi is listening on `8998`:

```bash
lsof -iTCP:8998 -sTCP:LISTEN
```

Check Moshi processes:

```bash
pgrep -af 'moshi_mlx.local_web'
```

Check Spring/Maven processes:

```bash
pgrep -af 'spring-boot:run|MoshiSpringGatewayApplication|moshi-spring-gateway'
```

## Stop Everything

First try normal shutdown from the terminal running Spring:

```text
Ctrl+C
```

If processes are still running, stop Spring and Moshi by port:

```bash
kill $(lsof -tiTCP:8080 -sTCP:LISTEN)
kill $(lsof -tiTCP:8998 -sTCP:LISTEN)
```

If a port command returns nothing, that service is already stopped.

If normal `kill` does not work:

```bash
kill -9 $(lsof -tiTCP:8080 -sTCP:LISTEN)
kill -9 $(lsof -tiTCP:8998 -sTCP:LISTEN)
```

## Restart Cleanly

```bash
kill $(lsof -tiTCP:8080 -sTCP:LISTEN) 2>/dev/null || true
kill $(lsof -tiTCP:8998 -sTCP:LISTEN) 2>/dev/null || true
cd /Users/riteshrajput/Desktop/FullDPV1/moshi-spring-gateway
./mvnw spring-boot:run
```

## Start Moshi Manually

Use this only if you want Moshi running outside Spring auto-start:

```bash
source ~/.venvs/moshi-mlx/bin/activate
python -m moshi_mlx.local_web -q 4 --host 0.0.0.0 --port 8998 --no-browser
```

Then start Spring without auto-starting Moshi:

```bash
cd /Users/riteshrajput/Desktop/FullDPV1/moshi-spring-gateway
MOSHI_AUTO_START=false ./mvnw spring-boot:run
```

## Run Tests

```bash
cd /Users/riteshrajput/Desktop/FullDPV1/moshi-spring-gateway
./mvnw clean test
```

## Useful Git Commands

```bash
git status -sb
git add .
git commit -m "your message"
git push
```
