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
| `panako-monitor-requests` | Monitor audio by URL (JSON) |
| `panako-monitor-results` | Monitor results with matched tracks |

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
