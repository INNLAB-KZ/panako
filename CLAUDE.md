# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Ozen Panako is a fork of [Panako](https://github.com/JorenSix/Panako) (IPEM, Ghent University) used for radio monitoring: it finds every catalog track playing in a long recording, with start/end timestamps. The fork adds a ClickHouse storage backend, an HTTP API, and Kafka / Redis Streams workers. Production runs the `OLAF` strategy on ClickHouse (see `docker-compose.yml`). User-facing docs: `README.md` (overview, worker modes, Redis semantics) and `API_README.md` (endpoints and all config options).

## Build & Test Commands

Java 17 is used for builds and runtime (Dockerfile); source level is 11, so do not use Java 12+ language features. `ffmpeg`/`ffprobe` must be on PATH for tests and runtime.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)

./gradlew build                      # compile + tests
./gradlew shadowJar                  # fat JAR: build/libs/panako-2.1-all.jar
./gradlew test                       # JUnit 5
./gradlew test --tests 'be.panako.http.RedisTaskDispatcherTest'          # one class
./gradlew test --tests 'be.panako.http.MonitorMatchAcceptanceTest.someMethod'  # one method

# Run the HTTP server locally (KEY=VALUE args override env and config.properties)
java --add-opens=java.base/java.nio=ALL-UNNAMED -cp build/libs/panako-2.1-all.jar \
  be.panako.http.PanakoHttpServer SERVER_PORT=8080 STRATEGY=OLAF OLAF_STORAGE=MEM

# Docker image (Dockerfile builds the JAR itself)
docker buildx build --platform linux/amd64,linux/arm64 -t innlabkz/ozen-panako:latest --push .
```

`--add-opens=java.base/java.nio=ALL-UNNAMED` is required for LMDB; `build.gradle` already sets it for tests.

## Architecture

### Two entry points
- `be.panako.cli.Panako`: the upstream CLI. It finds `Application` subclasses in `be.panako.cli` by reflection and dispatches by Trie prefix match.
- `be.panako.http.PanakoHttpServer`: the service entry point (the Docker `ENTRYPOINT`). `main()` loads `Config`, overrides every `Key` from same-named env vars, then from `KEY=VALUE` args. Args that are not a `Key` become system properties (`SERVER_PORT`, `REDIS_ENABLED`, etc.). It then starts the embedded `com.sun.net.httpserver` and, optionally, Kafka and Redis worker threads in the same JVM.

### Strategies and storage
`Strategy` is the core abstraction: `OlafStrategy` (Shazam-like, used in production), `PanakoStrategy` (pitch/tempo-robust, Gabor transform via JNI), and `PitchClassHistogramStrategy`. Each strategy lives under `src/main/java/be/panako/strategy/{olaf,panako,pch}/` with its own storage implementations. Select storage with `OLAF_STORAGE` / `PANAKO_STORAGE`: `LMDB`, `CLICKHOUSE`, `MEM`, `FILE`. The ClickHouse storage classes (`OlafStorageClickHouse`, `PanakoStorageClickHouse`) run `CREATE TABLE IF NOT EXISTS` in their constructors. Tables: `olaf_fingerprints`/`olaf_metadata` and `panako_*`.

Configuration: `Config` singleton + `Key` enum. Defaults are in `resources/defaults/config.properties`. To add a setting, add a `Key` with a default; env-var override then works automatically in the server.

### HTTP API (`be.panako.http`)
Routes are registered in `PanakoHttpServer`: `health`, `stats`, `store`, `store/url`, `store/fingerprints`, `store/rename`, `query`, `query/fingerprints`, `monitor`, `monitor/url`, `duplicates` (with `/scan` and `/delete`, handled in `DuplicatesHandler`), `delete`, `delete/by_id`, all under `/api/v1/`. Every route except `health` is wrapped in `ApiKeyFilter` (enabled when `API_KEY` is set). `HttpServer` contexts match by prefix, so register more specific paths (`/store/url`) before general ones (`/store`). A shared `ReentrantLock writeLock` serializes store/delete operations.

### Monitor pipeline
`MonitorHandler` holds the core recognition logic as static methods. HTTP handlers, `PanakoKafkaWorker`, `PanakoRedisStreamWorker`, and `resources/scripts/quality/RecognitionProbe.java` all reuse these methods:
- `monitorWithAbsoluteTimes` splits audio into overlapping windows (`MONITOR_STEP_SIZE`/`MONITOR_OVERLAP`), queries each window (in parallel when `MONITOR_PARALLEL_WINDOWS` > 1), then rescans gaps with fine windows (`*_FINE`). A failed, cancelled, or timed-out window invalidates the whole recording; it never returns partial success.
- `clusterWindows` / `MatchValidator` merge window hits into tracks and filter false positives (`MATCH_MIN_SCORE`, `MATCH_MIN_PERCENTAGE`).
- `buildResponseJson` produces the response shared by HTTP, Kafka, and Redis, including the waveform.
- Audio is decoded only through ffmpeg/ffprobe subprocesses (`extractAudioChunk*`, `getAudioDuration`, and `Key.DECODER_PIPE_COMMAND`).

### Async workers
- `PANAKO_WORKER_MODE` = `stage1` (default) | `refine` | `both` selects topics/streams and the consumer group. `both` starts one pool of each kind, and `KAFKA_WORKER_THREADS` / `REDIS_WORKER_THREADS` apply per pool.
- Kafka is enabled by `KAFKA_ENABLED=TRUE`. `KAFKA_MODE` is `ALL`, `STORE`, or `MONITOR`.
- Redis Streams is enabled by `REDIS_ENABLED=true`. `RedisTaskDispatcher` runs fresh and reclaimed (`XAUTOCLAIM`) messages serially, one task per worker. A heartbeat keeps the pending entry alive, and delivery is at least once.
- `AudioDownloadRouter` rewrites `AUDIO_CDN_HOST` URLs to `AUDIO_ORIGIN_BASE_URL` before download. When the origin download fails, it retries the CDN once.
- `StoreResourceCleanup` rolls back partial fingerprints after a failed store. `LongAudioDuplicateCheck` checks audio longer than 30 s in overlapping 30-second windows, requiring consistent reference alignment across the recording.

## Deployment & Ops

- Compose files are split by role: `docker-compose.yml` (API + store worker + ClickHouse), `.store.yml`, `.worker.yml` (multi-instance monitor workers), `.query.yml`, `.clickhouse.yml`, `.redis.yml`. They pull `innlabkz/ozen-panako:latest`, so rebuild and push the image to deploy code changes.
- `resources/scripts/quality/` holds recognition regression tooling. To compare a baseline JAR against a candidate JAR, run `RecognitionProbe` + `compare.py` (see that README); the Python tests there are `test_*.py`. Dated rollout and incident reports live in `quality/reports/` and the dated subdirectories. Keep production credentials, audio, and result artifacts out of Git.
- ClickHouse shell: `docker exec -it ozen-clickhouse clickhouse-client`.
