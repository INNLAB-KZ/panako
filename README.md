# Ozen Panako

Acoustic fingerprinting system for radio monitoring. Identifies tracks playing in long audio recordings with precise start/end timestamps. Based on [Panako](https://github.com/JorenSix/Panako) by Joren Six / IPEM, Ghent University.

## Features

- **Radio monitoring** — identify all tracks in a radio recording with start/end times
- **BPM/pitch tolerance** — PANAKO strategy handles speed and pitch changes up to +/-20%
- **Scalable storage** — ClickHouse backend for catalogs up to 30M+ tracks
- **Async processing** — Kafka integration for batch store and monitor operations
- **REST API** — HTTP endpoints for store, query, monitor, delete
- **Waveform** — monitor response includes per-second peak amplitude
- **Boundary refinement** — automatic precision improvement for track start/end detection
- **False positive filtering** — configurable score and match percentage thresholds

## Quick Start

```bash
docker compose up
```

This starts:
- **Panako** API on `http://localhost:8344`
- **ClickHouse** on `localhost:8123`
- **Kafka** on `localhost:9092`

### Store a track

```bash
curl -X POST http://localhost:8344/api/v1/store -F "audio=@ISRC.mp3"
```

Or by URL:

```bash
curl -X POST http://localhost:8344/api/v1/store/url \
  -H "Content-Type: application/json" \
  -d '{"audio_url": "https://example.com/ISRC.mp3", "filename": "ISRC.mp3"}'
```

### Monitor a radio recording

```bash
curl -X POST http://localhost:8344/api/v1/monitor -F "audio=@radio_recording.mp3"
```

Response:

```json
{
  "status": "ok",
  "processing_time_ms": 15230,
  "unique_tracks_count": 17,
  "matches": [
    {
      "identifier": 1612789453,
      "isrc": "USRC17607839",
      "filename": "USRC17607839.mp3",
      "query_start_seconds": 60.0,
      "query_start_time": "00:01:00",
      "query_end_seconds": 255.4,
      "query_end_time": "00:04:15",
      "score": 4500,
      "match_percentage": 1.0,
      "window_hits": 9
    }
  ],
  "waveform": [0.125, 0.340, 0.892, ...]
}
```

### Query a fragment

```bash
curl -X POST http://localhost:8344/api/v1/query -F "audio=@fragment.mp3"
```

## Build

Requires Java 17 and ffmpeg.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew shadowJar
```

Build Docker image:

```bash
./gradlew shadowJar
docker buildx build --platform linux/amd64,linux/arm64 -t innlabkz/ozen-panako:latest --push .
```

## Architecture

```
                          +-------------------+
  HTTP API (8344) ------->|                   |
                          |   Panako Server   |------> ClickHouse (fingerprints)
  Kafka consumer -------->|                   |
                          +-------------------+
                                |
                          ffmpeg (audio decode)
```

### Strategy: PANAKO (recommended)

Uses Gabor transform with pitch-invariant fingerprints. Handles BPM and speed differences between reference and radio versions up to +/-20%.

### Storage: ClickHouse

Columnar database with LZ4 compression (4-5x). Scales to 30M+ tracks. Supports concurrent reads and writes.

| Tracks | Disk | RAM needed |
|---|---|---|
| 100K | ~30 GB | 8 GB |
| 1M | ~300 GB | 32 GB |
| 10M | ~3 TB | 128 GB |
| 30M | ~8 TB | 256 GB+ |

### Kafka (optional)

Async store and monitor via topics. Enable with `KAFKA_ENABLED=TRUE`.

| Topic | Description |
|---|---|
| `panako-store-requests` | Store audio by URL (JSON) |
| `panako-store-results` | Store results |
| `panako.monitor.request` | Monitor audio by URL (JSON) — Stage 1 |
| `panako.monitor.response` | Monitor results — Stage 1 |
| `panako.monitor.refine.request` | Monitor audio by URL (JSON) — Stage 3 refine |
| `panako.monitor.refine.response` | Monitor results — Stage 3 refine |

#### Worker modes

A single `panako-api` binary runs in one of three worker modes, selected via
the `PANAKO_WORKER_MODE` env var:

| Mode | Topics consumed / produced | Consumer group |
|---|---|---|
| `stage1` *(default)* | `panako.monitor.request` → `panako.monitor.response` (+ store topics if `KAFKA_MODE=ALL\|STORE`) | `panako` (or `KAFKA_GROUP_ID`) |
| `refine` | `panako.monitor.refine.request` → `panako.monitor.refine.response` | `panako-worker-refine` |
| `both` | Both pools at once — one stage1 consumer + one refine consumer in the same JVM | `panako` **and** `panako-worker-refine` (one consumer each) |

Request/response JSON schemas are identical across modes — only the topic names
and consumer group differ. All three modes can coexist in the same cluster;
refine and stage1 consumers never compete because they use distinct consumer
groups and subscribe to different topics.

In `both` mode, `KAFKA_WORKER_THREADS` is applied per pool — setting it to `N`
spawns `N` stage1 workers **and** `N` refine workers in the same JVM.

Run a refine-only worker (example snippet):

```yaml
services:
  panako-refine-1:
    image: innlabkz/ozen-panako:latest
    environment:
      STRATEGY: OLAF
      OLAF_STORAGE: CLICKHOUSE
      OLAF_CLICKHOUSE_URL: "jdbc:ch://10.0.0.6:8123/default?user=default&password=${CLICKHOUSE_PASSWORD}"
      KAFKA_ENABLED: "TRUE"
      KAFKA_MODE: MONITOR
      KAFKA_BOOTSTRAP_SERVERS: "10.0.0.6:39092"
      PANAKO_WORKER_MODE: refine          # <-- picks refine topics + group
```

Run a combined worker that handles both Stage 1 and refine traffic:

```yaml
services:
  panako-both-1:
    image: innlabkz/ozen-panako:latest
    environment:
      # ... same Panako / ClickHouse / Kafka config as above ...
      PANAKO_WORKER_MODE: both            # <-- spawns stage1 + refine pools
```

Keep existing stage1 workers untouched (no env change needed — default is `stage1`).

See `docker-compose.worker.yml` for a full multi-instance example.

## Configuration

Via environment variables (Docker), CLI arguments, or config file.

| Parameter | Default | Description |
|---|---|---|
| `STRATEGY` | OLAF | `OLAF` or `PANAKO` |
| `PANAKO_STORAGE` | LMDB | `LMDB`, `CLICKHOUSE`, or `MEM` |
| `PANAKO_CLICKHOUSE_URL` | `jdbc:ch://localhost:8123/default` | ClickHouse connection |
| `KAFKA_ENABLED` | FALSE | Enable Kafka integration |
| `KAFKA_BOOTSTRAP_SERVERS` | localhost:9092 | Kafka brokers |
| `MONITOR_STEP_SIZE` | 30 | Monitor window size (seconds) |
| `MONITOR_OVERLAP` | 10 | Monitor window overlap (seconds) |
| `MATCH_MIN_SCORE` | 20 | Minimum score to accept a match |
| `MATCH_MIN_PERCENTAGE` | 0.5 | Minimum match percentage (0.0-1.0) |

See [API_README.md](API_README.md) for full API documentation and all configuration options.

## ClickHouse CLI

```bash
docker exec -it ozen-clickhouse clickhouse-client
```

```sql
SELECT count() FROM panako_metadata FINAL;
SELECT count() FROM panako_fingerprints;
SELECT table, formatReadableSize(total_bytes) FROM system.tables WHERE database = 'default';
```

## License

AGPL-3.0 (based on upstream Panako).

docker compose kill panako-api && docker compose up -d panako-api 
