# Panako HTTP API

Embedded REST API for the Panako acoustic fingerprinting system. No external frameworks — uses JDK built-in `com.sun.net.httpserver`.

Filename is used as stable identifier key — files named `ISRC.mp3`, `ISRC.aac`, `ISRC.m4a` all map to the same identifier via murmurhash3 of the ISRC.

## Build

```bash
./gradlew shadowJar
```

## Run

### HTTP API Server

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
  -cp build/libs/panako-2.1-all.jar \
  be.panako.http.PanakoHttpServer
```

With custom parameters:

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
  -cp build/libs/panako-2.1-all.jar \
  be.panako.http.PanakoHttpServer \
  SERVER_PORT=9090 \
  STRATEGY=OLAF \
  SERVER_THREAD_POOL_SIZE=10
```

### CLI (unchanged)

All existing CLI commands work as before:

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED -jar build/libs/panako-2.1-all.jar store audio.mp3
java --add-opens=java.base/java.nio=ALL-UNNAMED -jar build/libs/panako-2.1-all.jar query fragment.mp3
java --add-opens=java.base/java.nio=ALL-UNNAMED -jar build/libs/panako-2.1-all.jar stats
```

### Docker

```bash
./gradlew shadowJar
docker buildx build --platform linux/amd64,linux/arm64 -t innlabkz/ozen-panako:latest --push .
```

Or run with docker compose:

```bash
docker compose up
```

The API will be available at `http://localhost:8344` (mapped to container port 8080).

Database files are persisted in `./data/panako-db`.

## Authentication

All endpoints except `/api/v1/health` require an API key when `API_KEY` is configured.

Pass the key via `X-API-Key` header (recommended) or `api_key` query parameter:

```bash
# Header (recommended)
curl -H "X-API-Key: your-secret-key" http://localhost:8080/api/v1/stats

# Query parameter
curl http://localhost:8080/api/v1/stats?api_key=your-secret-key
```

Set the key via environment variable or config:

```bash
# Environment variable
API_KEY=your-secret-key

# Docker compose (.env file)
API_KEY=your-secret-key
```

If `API_KEY` is empty or not set, authentication is disabled (all requests allowed).

Unauthorized requests receive:

```json
{"status": "error", "message": "Invalid or missing API key"}
```

## API Endpoints

### `GET /api/v1/health`

Health check.

```bash
curl http://localhost:8080/api/v1/health
```

Response:

```json
{"status": "ok", "version": "2.1-api"}
```

### `GET /api/v1/stats`

Database statistics.

```bash
curl http://localhost:8080/api/v1/stats
```

Response:

```json
{
  "status": "ok",
  "fingerprint_count": 125000,
  "audio_items_count": 50,
  "strategy": "OLAF"
}
```

### `POST /api/v1/store`

Store audio fingerprints. Accepts `multipart/form-data` with field name `audio`.

OLAF indexes audio longer than five minutes in overlapping five-minute chunks under one resource ID. Stored fingerprint times remain relative to the full track. For OLAF, content deduplication of audio longer than 30 seconds queries overlapping 30-second windows across the whole file and requires every window to match the same track at a consistent alignment, with a duration within 5 seconds. The response reports the stored fingerprint count. If any chunk fails, indexing returns an error and removes partial ClickHouse rows.

Filename should be `ISRC.ext` (e.g. `USRC17607839.mp3`). The ISRC is extracted and returned in the response.

```bash
curl -X POST http://localhost:8080/api/v1/store -F "audio=@USRC17607839.mp3"
```

Response:

```json
{
  "status": "ok",
  "identifier": 1612789453,
  "isrc": "USRC17607839",
  "filename": "USRC17607839.mp3",
  "duration_seconds": 195.4,
  "fingerprints_count": 1250,
  "processing_time_ms": 2430
}
```

If the same filename/ISRC is already stored (hash duplicate):

```json
{
  "status": "already_exists",
  "identifier": 1612789453,
  "isrc": "USRC17607839",
  "filename": "USRC17607839.mp3",
  "duration_seconds": 195.4,
  "fingerprints_count": 1250
}
```

If the uploaded audio is the same content as an already-indexed track under a different filename/ISRC (content duplicate, ≥99% fingerprint match), the upload is **not** indexed and the canonical match is returned:

```json
{
  "status": "already_indexed_match",
  "identifier": 1612789453,
  "isrc": "USRC17607839",
  "filename": "USRC17607839.mp3",
  "title": "Artist:Track",
  "audio_url": "https://example.com/USRC17607839.mp3",
  "match_percentage": 99.8,
  "submitted_filename": "QZHN82412345.mp3",
  "submitted_isrc": "QZHN82412345"
}
```

`identifier`/`isrc`/`filename`/`title`/`audio_url` describe the canonical already-indexed track. `submitted_*` fields echo what the caller uploaded. The store endpoint never indexes the upload in this case — clients should use the canonical `identifier` going forward.

If the uploaded file is 0 bytes, no indexing happens:

```json
{
  "status": "skipped_empty",
  "identifier": 1612789453,
  "isrc": "USRC17607839",
  "filename": "USRC17607839.mp3",
  "title": null,
  "audio_url": null
}
```

All status responses (`ok`, `already_exists`, `already_indexed_match`, `skipped_empty`) are HTTP **200 OK**. Only `400`/`500` indicate real errors.

### `POST /api/v1/store/url`

Store audio fingerprints by downloading from a URL. Accepts `application/json`.

```bash
curl -X POST http://localhost:8080/api/v1/store/url \
  -H "Content-Type: application/json" \
  -d '{"audio_url": "https://example.com/USRC17607839.mp3", "filename": "USRC17607839.mp3"}'
```

- `audio_url` — required, URL to download the audio from
- `filename` — optional, if omitted it is derived from the URL path

Response:

```json
{
  "status": "ok",
  "identifier": 1612789453,
  "isrc": "USRC17607839",
  "filename": "USRC17607839.mp3",
  "audio_url": "https://example.com/USRC17607839.mp3",
  "duration_seconds": 195.4,
  "fingerprints_count": 1250,
  "processing_time_ms": 2430
}
```

Both `already_exists` (hash duplicate) and `already_indexed_match` (content duplicate ≥99%) checks work the same as `POST /api/v1/store`. The `already_indexed_match` response on this endpoint additionally includes the original `submitted_audio_url`. A 0-byte download returns `status:"skipped_empty"` without indexing.

### `POST /api/v1/store/rename`

Change the `path` column on an existing `olaf_metadata` / `panako_metadata` row **without re-running fingerprinting**. The underlying fingerprints stay attached to the same `resource_id`; only the descriptive `path` label changes. Useful when the canonical filename of an already-indexed track changes (e.g. a temp upload name like `/tmp/1000067653.mp3` should be relabelled to its real ISRC filename `RUAGT2342680.m4a`).

Accepts `application/json` with one of these shapes:

```bash
# by identifier (preferred — directly addresses the resource_id)
curl -X POST http://localhost:8080/api/v1/store/rename \
  -H "Content-Type: application/json" \
  -d '{"identifier": 1612789453, "new_path": "RUAGT2342680.m4a"}'

# by old_path (resource_id is recomputed via the same identifier hash function)
curl -X POST http://localhost:8080/api/v1/store/rename \
  -H "Content-Type: application/json" \
  -d '{"old_path": "/tmp/1000067653.mp3", "new_path": "RUAGT2342680.m4a"}'
```

- `new_path` — required, new value to write into the `path` column
- `identifier` — Int64 `resource_id` from `olaf_metadata` (preferred when known)
- `old_path` — string; ignored when `identifier` is supplied. Used as input to `FileUtils.getIdentifier()` to derive the `resource_id`. Must produce the exact same hash that was used at index time.

Response on success (HTTP 200):

```json
{
  "status": "ok",
  "identifier": 1612789453,
  "new_path": "RUAGT2342680.m4a"
}
```

When no metadata row exists for the resolved `resource_id` (HTTP 404):

```json
{
  "status": "not_found",
  "identifier": 1612789453,
  "new_path": "RUAGT2342680.m4a"
}
```

Notes:
- Only supported on ClickHouse storage. LMDB backends serialize path inside the metadata blob and cannot be patched in place — the endpoint returns `not_found` on LMDB.
- Implementation: `SELECT FINAL` reads the current `duration`/`num_fingerprints`/`title`/`audio_url` and a new row is inserted with the same `resource_id` plus the new `path`. The `ReplacingMergeTree` engine collapses the duplicate on the next background merge.

### `POST /api/v1/store/fingerprints`

Store pre-computed fingerprints directly without sending audio. Accepts `application/json`.

This is useful when fingerprints are extracted on the client side (e.g. via `panako print`) and only the compact fingerprint data needs to be sent to the server.

```bash
curl -X POST http://localhost:8080/api/v1/store/fingerprints \
  -H "Content-Type: application/json" \
  -d '{
    "filename": "USRC17607839.mp3",
    "duration": 195.4,
    "fingerprints": [
      {"hash": 123456789, "t1": 10, "f1": 200},
      {"hash": 987654321, "t1": 20, "f1": 150}
    ]
  }'
```

- `filename` — required, used to derive identifier and ISRC
- `duration` — required, audio duration in seconds
- `identifier` — optional, if omitted it is derived from the filename
- `fingerprints` — required, array of `{hash, t1, f1}` objects

Response:

```json
{
  "status": "ok",
  "identifier": 1612789453,
  "isrc": "USRC17607839",
  "filename": "USRC17607839.mp3",
  "duration_seconds": 195.4,
  "fingerprints_count": 2,
  "processing_time_ms": 5
}
```

Duplicate check works the same as other store endpoints.

### `POST /api/v1/query`

Query for matches. Accepts `multipart/form-data` with field name `audio`.

Results are validated to filter false positives:
- **Quality check**: score >= `MATCH_MIN_SCORE` (default 20) and match_percentage >= `MATCH_MIN_PERCENTAGE` (default 0.5)
- **Match density**: short clips (< 15s) need >= 8 matches; longer clips need >= max(duration x 0.15, 20)
- **Duration check**: query must not be longer than matched reference + 5 seconds

```bash
curl -X POST http://localhost:8080/api/v1/query -F "audio=@fragment.mp3"
```

Response:

```json
{
  "status": "ok",
  "query_duration_seconds": 10.5,
  "processing_time_ms": 320,
  "matches": [
    {
      "identifier": 1612789453,
      "isrc": "USRC17607839",
      "filename": "USRC17607839.mp3",
      "match_start_seconds": 45.2,
      "match_end_seconds": 55.7,
      "query_start_seconds": 0.0,
      "query_end_seconds": 10.5,
      "score": 193,
      "time_factor": 1.001,
      "frequency_factor": 1.000,
      "match_percentage": 1.0
    }
  ]
}
```

No match returns `"matches": []`.

### `POST /api/v1/query/fingerprints`

Query for matches using pre-computed fingerprints (no audio needed). Accepts `application/json`.

```bash
curl -X POST http://localhost:8080/api/v1/query/fingerprints \
  -H "Content-Type: application/json" \
  -d '{
    "fingerprints": [
      {"hash": 123456789, "t1": 10, "f1": 200},
      {"hash": 987654321, "t1": 20, "f1": 150}
    ]
  }'
```

Response format is identical to `POST /api/v1/query`.

### `POST /api/v1/monitor`

Monitor a long audio file (e.g. radio recording) and find all matching tracks. The audio is split into overlapping windows and each window is queried separately. Results are **deduplicated by track** — multiple window hits for the same track are merged into a single entry with the overall time range. Accepts `multipart/form-data` with field name `audio`.

**How it works:**
1. Audio is split into overlapping windows (configurable via `MONITOR_STEP_SIZE` and `MONITOR_OVERLAP`)
2. Each window is queried against the fingerprint database
3. Results are merged by track identifier with absolute timestamps
4. **Boundary refinement**: if the match doesn't start at the beginning or end of the reference track, additional queries are made to find the precise start/end times
5. False positives are filtered by `MONITOR_MIN_SCORE` and `MONITOR_MIN_PERCENTAGE`

```bash
curl -X POST http://localhost:8080/api/v1/monitor -F "audio=@radio_recording.mp3"
```

Response:

```json
{
  "status": "ok",
  "processing_time_ms": 15230,
  "unique_tracks_count": 2,
  "matches": [
    {
      "identifier": 1612789453,
      "isrc": "USRC17607839",
      "filename": "USRC17607839.mp3",
      "query_start_seconds": 60.0,
      "query_start_time": "00:01:00",
      "query_end_seconds": 255.4,
      "query_end_time": "00:04:15",
      "match_start_seconds": 0.0,
      "match_end_seconds": 195.4,
      "score": 4500,
      "time_factor": 1.001,
      "frequency_factor": 1.000,
      "match_percentage": 1.0,
      "window_hits": 9
    },
    {
      "identifier": 987654321,
      "isrc": "GBAYE0601498",
      "filename": "GBAYE0601498.mp3",
      "query_start_seconds": 300.0,
      "query_start_time": "00:05:00",
      "query_end_seconds": 520.0,
      "query_end_time": "00:08:40",
      "match_start_seconds": 10.2,
      "match_end_seconds": 230.0,
      "score": 6000,
      "time_factor": 1.000,
      "frequency_factor": 1.000,
      "match_percentage": 0.9,
      "window_hits": 11
    }
  ]
}
```

Response fields:

| Field | Description |
|---|---|
| `unique_tracks_count` | Number of distinct tracks identified |
| `query_start_seconds` | When the track starts in your recording (seconds) |
| `query_start_time` | Same as above in `HH:mm:ss` format |
| `query_end_seconds` | When the track ends in your recording (seconds) |
| `query_end_time` | Same as above in `HH:mm:ss` format |
| `match_start_seconds` | Where the match starts in the reference track |
| `match_end_seconds` | Where the match ends in the reference track |
| `score` | Total fingerprint hit count across all windows |
| `time_factor` | Time stretching factor (1.0 = no change) |
| `frequency_factor` | Pitch shifting factor (1.0 = no change) |
| `match_percentage` | Fraction of seconds with matching fingerprints (0.0-1.0) |
| `window_hits` | How many monitoring windows matched this track |

### `POST /api/v1/monitor/url`

Monitor audio downloaded from a URL. Accepts `application/json`.

```bash
curl -X POST http://localhost:8080/api/v1/monitor/url \
  -H "Content-Type: application/json" \
  -d '{"audio_url": "https://example.com/radio_recording.mp3"}'
```

- `audio_url` — required, URL to download the audio from
- `filename` — optional, if omitted it is derived from the URL path

Response format is identical to `POST /api/v1/monitor`.

### `POST /api/v1/delete`

Delete fingerprints. Accepts `multipart/form-data` with the original audio file.

```bash
curl -X POST http://localhost:8080/api/v1/delete -F "audio=@USRC17607839.mp3"
```

### `POST /api/v1/delete/by_id`

Delete fingerprints **and** metadata for a known `resource_id` without needing the original audio file. Accepts `application/json`. It is `delete/by_ids` with one id.

```bash
curl -X POST http://localhost:8080/api/v1/delete/by_id \
  -H "Content-Type: application/json" \
  -d '{"identifier": 1979144137}'
```

- `identifier` (or alias `resource_id`) — required, the Int64 row key from `olaf_metadata`

Response (HTTP 200):

```json
{
  "status": "ok",
  "identifier": 1979144137,
  "rows_deleted": 13762
}
```

`rows_deleted` has the same meaning as in `delete/by_ids`.

### `POST /api/v1/delete/by_ids`

Delete fingerprints and metadata of up to 100 `resource_id`s in one call. Use it to remove wrong audio. It does not check duplicate pairs and does not write to the duplicate deletion log; use `POST /api/v1/duplicates/delete` for duplicates. OLAF + ClickHouse only.

```bash
curl -X POST http://localhost:8080/api/v1/delete/by_ids \
  -H "X-API-Key: $API_KEY" -H "Content-Type: application/json" \
  -d '{"identifiers": [1075536417, 1087869885]}'
```

- `identifiers` — required, 1 to 100 resource ids. An empty list, more than 100 ids or a non-numeric id gives HTTP 400.

Response (HTTP 200):

```json
{
  "status": "ok",
  "rows_deleted": {"1075536417": 14120, "1087869885": 0}
}
```

- Keys are the ids as strings. The value is the fingerprint count recorded in the track metadata at store time. `0` means the id has no metadata: it is already deleted or never existed. Fingerprint rows without metadata are still deleted, but they count as `0`.
- A repeated call with the same ids returns `200` with every value `0`.
- HTTP 500 with the ClickHouse error in `message` means the call failed. Nothing is reported as deleted; retry the same ids. A failure can leave the metadata deleted and the fingerprints in place; the retry queues the fingerprint delete again.

How the delete runs:
- `olaf_fingerprints` is sorted by `hash`, so a delete by `resource_id` cannot use the index. A lightweight delete is a mutation that rewrites the row mask of the whole table, which takes minutes on production. One call issues one statement per table, never one per id.
- The metadata delete is synchronous (`lightweight_deletes_sync = 2`, all replicas, at most 45 s). Query and monitor drop matches of tracks without metadata, so deleted ids stop matching when the call returns. `/api/v1/stats` shows the lower `audio_items_count` at once.
- The fingerprint delete is queued (`lightweight_deletes_sync = 0`) and the call does not wait for it. `fingerprint_count` in `/api/v1/stats` drops when ClickHouse finishes the mutation. Pending mutations are applied together in one pass. Follow them with `SELECT mutation_id, is_done, parts_to_do FROM system.mutations WHERE table = 'olaf_fingerprints' AND NOT is_done`. Rows stored after the call, for example a corrected re-store of the same id, are not affected.
- Each call adds two mutations, one per table. ClickHouse delays new mutations at 500 unfinished ones and rejects them at 1000 (`number_of_mutations_to_delay` / `number_of_mutations_to_throw`). Send large clean-ups in batches of 100 ids, for example 56 calls for 5 600 ids, and do not send single-id calls in a loop.

### Duplicate tracks: `/api/v1/duplicates`

Finds reference tracks that are copies of the same master, for example one recording released under several ISRCs. Requires `STRATEGY=OLAF` and `OLAF_STORAGE=CLICKHOUSE`.

**How similarity is measured.** Two copies of one master share many fingerprint hashes at one constant time offset. For a pair of tracks, the coverage of a track is the fraction of its seconds that have such aligned hashes. `similarity` is the lower of the two coverages, from 0 to 1. Hashes shared by more than `DUPLICATE_SCAN_MAX_TRACKS_PER_HASH` fingerprints are ignored. The scan compares only tracks whose durations differ by at most `DUPLICATE_SCAN_MAX_DURATION_DIFF_SECONDS` and whose offset is at most `DUPLICATE_SCAN_MAX_OFFSET_SECONDS`. Pairs at or above `DUPLICATE_MIN_SIMILARITY` (0.95) are labelled `duplicate`, and lower pairs are labelled `similar`.

Calibration on production pairs (2026-10-15): copies of one master scored 0.97–1.0. Two pairs confirmed by hand scored 0.92 and 0.70; the 0.70 pair differs by 7 s in duration. Unrelated tracks share fewer than 10 aligned hashes. Check `similar` groups manually before deleting.

#### `POST /api/v1/duplicates/scan`

Starts a background scan of all fingerprints. The scan reads `olaf_fingerprints` in about 118 hash ranges, one after another, with `max_threads = DUPLICATE_SCAN_MAX_THREADS`. On the production catalog (2.35 billion rows) it is expected to take about 30 minutes. Returns HTTP 202, or HTTP 409 when a scan is already running.

```bash
curl -X POST http://localhost:8080/api/v1/duplicates/scan -H "X-API-Key: $API_KEY"
```

Run the scan once. The results are stored in ClickHouse, and listing and deleting read only these tables. When a finished scan exists, `POST /scan` returns HTTP 409 unless the body is `{"force": true}`. A forced scan replaces the previous results in `olaf_duplicate_pairs` and `olaf_duplicate_tracks`, and is recorded in `olaf_duplicate_scans`. A failed scan leaves the previous results in place. When `olaf_fingerprints` is a `ReplicatedMergeTree`, the result tables are created as `ReplicatedMergeTree` too, so every replica serves the same results. Until a replica has received all pairs of the last scan, list and delete on that replica return HTTP 503. Scan progress is kept in memory. If the server restarts during a scan, start it again.

#### `GET /api/v1/duplicates/scan`

```json
{
  "status": "running",
  "scan_id": "20261015T101500Z",
  "partitions_done": 41,
  "partitions_total": 118,
  "candidate_pairs": 24120,
  "pairs": 0,
  "started_at": "2026-10-15T10:15:00Z",
  "finished_at": null,
  "last_finished_scan": {"scan_id": "20261014T090000Z", "started_at": "...", "finished_at": "...", "pairs": 23950}
}
```

`status` is `idle`, `running`, `finished` or `failed` (with `error`).

#### `GET /api/v1/duplicates?min_similarity=0.95&limit=100&offset=0`

Lists groups of similar tracks from the last finished scan. Tracks are grouped when they are linked by pairs at or above `min_similarity`, directly or through other tracks. The default `min_similarity` is `DUPLICATE_SCAN_MIN_SIMILARITY` (0.5). Tracks deleted since the scan are left out.

```json
{
  "status": "ok",
  "scan_id": "20261015T101500Z",
  "min_similarity": 0.950,
  "duplicate_similarity": 0.950,
  "total_groups": 1,
  "offset": 0,
  "groups": [{
    "label": "duplicate",
    "max_similarity": 0.991,
    "min_similarity": 0.974,
    "recommended_keep": 1000066942,
    "tracks": [{
      "resource_id": 1000066942, "isrc": "1000066942", "path": "1000066942.mp3",
      "title": null, "audio_url": null, "duration": 228.7,
      "fingerprints": 14236, "fingerprints_per_second": 62.2,
      "mean_similarity": 0.982, "recommended": true
    }],
    "pairs": [{
      "resource_a": 1000066942, "resource_b": 1584800188, "label": "duplicate",
      "similarity": 0.991, "coverage_a": 0.991, "coverage_b": 0.991,
      "offset_seconds": 0.016, "aligned_hits": 11302
    }]
  }]
}
```

`recommended_keep` is the copy that represents the group best for recognition. It is chosen by the highest `mean_similarity` to the other members, then the highest `fingerprints_per_second` (more hashes to match in noisy broadcasts), then the longest duration. The choice is a suggestion only; you can keep any track.

#### `POST /api/v1/duplicates/delete`

Deletes copies of a kept track. Each track must have a stored pair with `keep` in the last scan, with `similarity` at least `min_similarity` (default `DUPLICATE_SCAN_MIN_SIMILARITY`). With `"verify": true`, tracks are compared with `keep` on the current fingerprints instead, without duration limits; use this for tracks that are in one group only through other members. If any track fails, the request deletes nothing and returns HTTP 409. `dry_run` is `true` by default; set it to `false` to delete. At most 100 ids per request.

```bash
curl -X POST http://localhost:8080/api/v1/duplicates/delete \
  -H "X-API-Key: $API_KEY" -H "Content-Type: application/json" \
  -d '{"keep": 1000066942, "delete": [1584800188, 1000082958], "dry_run": false}'
```

```json
{
  "status": "ok",
  "keep": 1000066942,
  "min_similarity": 0.500,
  "tracks": [{
    "resource_id": 1584800188, "isrc": "FR96X2648609", "similarity": 0.991, "verified": true,
    "coverage_keep": 0.991, "coverage_track": 0.991, "offset_seconds": 0.000, "aligned_hits": 11302,
    "deleted": true, "fingerprints_deleted": 14120
  }]
}
```

The response also has `verified_on`: `scan:<scan_id>` or `fingerprints`. `status` is `dry_run`, `ok`, or `rejected` (HTTP 409). The `"verify": true` check reads the `resource_id` column of the whole table, so expect about 10 seconds per request. Every deleted track is logged in `olaf_duplicate_deletions`, which rescans do not clear. Deletion uses lightweight `DELETE` on `olaf_fingerprints` and `olaf_metadata`. Add the deleted ISRCs as aliases of the kept track in trackDB **before** deleting, or monitor results will lose those ISRCs.

#### `GET /api/v1/duplicates/deletions?limit=100&offset=0`

Lists deleted copies, newest first, with the ISRC kept in their place. Use it to add trackDB aliases.

```json
{
  "status": "ok",
  "offset": 0,
  "deletions": [{
    "deleted_at": "2026-10-15T11:02:00Z", "keep": 1000066942, "keep_isrc": "1000066942",
    "resource_id": 1584800188, "isrc": "FR96X2648609", "similarity": 0.991,
    "verified_on": "scan:20261015T101500Z", "fingerprints_deleted": 14120
  }]
}
```

## Configuration

All parameters can be set via CLI arguments (`KEY=VALUE`), system properties (`-DKEY=VALUE`), environment variables, or `~/.panako/config.properties`.

### Server

| Parameter | Default | Description |
|---|---|---|
| `API_KEY` | _(empty)_ | API key for authentication. If empty, auth is disabled |
| `SERVER_PORT` | 8080 | HTTP server port |
| `SERVER_MAX_UPLOAD_SIZE_MB` | 256 | Maximum upload file size in MB |
| `SERVER_THREAD_POOL_SIZE` | 10 | HTTP handler thread pool size |
| `STRATEGY` | OLAF | Fingerprinting algorithm (`OLAF` or `PANAKO`) |

### Query filtering

| Parameter | Default | Description |
|---|---|---|
| `MATCH_MIN_SCORE` | 20 | Minimum score (fingerprint hits) to accept a query match |
| `MATCH_MIN_PERCENTAGE` | 0.5 | Minimum match percentage (0.0-1.0) for query results |

### Monitor

| Parameter | Default | Description |
|---|---|---|
| `MONITOR_STEP_SIZE` | 30 | Window size in seconds |
| `MONITOR_OVERLAP` | 10 | Window overlap in seconds (step = STEP_SIZE - OVERLAP) |
| `MONITOR_MIN_SCORE` | 20 | Minimum total score to accept a monitor match |
| `MONITOR_MIN_PERCENTAGE` | 0.5 | Minimum match percentage (0.0-1.0) for monitor results |
| `MONITOR_REFINE_THRESHOLD` | 5.0 | Gap in seconds to trigger boundary refinement |
| `MONITOR_REFINE_CHUNK_SIZE` | 30 | Chunk size in seconds for refinement queries |

Example — stricter filtering and larger monitor windows:

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
  -cp build/libs/panako-2.1-all.jar \
  be.panako.http.PanakoHttpServer \
  MATCH_MIN_SCORE=50 \
  MONITOR_MIN_PERCENTAGE=0.7 \
  MONITOR_STEP_SIZE=30 \
  MONITOR_OVERLAP=10
```

### Duplicate scan

| Parameter | Default | Description |
|---|---|---|
| `DUPLICATE_SCAN_PARTITION_ROWS` | 20000000 | Fingerprint rows per scan query. Larger values are faster but use more ClickHouse memory (76M rows used about 13 GB) |
| `DUPLICATE_SCAN_MAX_TRACKS_PER_HASH` | 256 | Ignore hashes with more fingerprints than this |
| `DUPLICATE_SCAN_MAX_OFFSET_SECONDS` | 10 | Largest time offset between two copies |
| `DUPLICATE_SCAN_MAX_DURATION_DIFF_SECONDS` | 15 | Largest duration difference between two compared tracks |
| `DUPLICATE_SCAN_MIN_SIMILARITY` | 0.5 | Pairs below this are not stored; default for listing and delete checks |
| `DUPLICATE_SCAN_MAX_THREADS` | 4 | ClickHouse `max_threads` for scan queries |
| `DUPLICATE_MIN_SIMILARITY` | 0.95 | Similarity from which a pair or group is labelled `duplicate` |

## Identifier Logic

The `identifier` is a stable numeric key derived from the filename (without extension):

- `USRC17607839.mp3` -> murmurhash3(`"USRC17607839"`) -> same ID regardless of format
- `USRC17607839.aac` -> same ID
- `1855.mp3` -> `1855` (numeric filenames used directly)

This ensures the same ISRC always maps to the same identifier, enabling duplicate detection and consistent query results.

## Concurrency

LMDB allows concurrent reads but only a single writer. Store and delete operations are serialized with a lock. Query and stats requests run concurrently without blocking.

## Async queue (Redis Streams)

When `REDIS_ENABLED=true`, workers also consume tasks from Redis Streams instead of (or alongside) the synchronous HTTP API. Stream names default to the Kafka topic names so the same orchestrator schema works for both backends.

### Streams

| Request stream | Result stream | Purpose |
|---|---|---|
| `panako-store-requests` | `panako-store-results` | Store audio by URL (download + fingerprint + index) |
| `panako-store-patch` | `panako-store-patch-results` | Patch `olaf_metadata` rows without re-fingerprinting (rename, etc) |
| `panako.monitor.request` | `panako.monitor.response` | Monitor long audio for multiple matches |
| `panako.monitor.refine.request` | `panako.monitor.refine.response` | Stage 3 refine (dedicated pool, `PANAKO_WORKER_MODE=refine`) |

Set `REDIS_STORE_PATCH_ENABLED=false` to exclude `panako-store-patch` from a Redis
STORE/ALL worker while continuing ingestion. Default: `true`. This pauses maintenance
only for workers with this setting; queued requests remain in Redis.

Each request is XADDed with `{"key": "<request_id>", "value": "<json>"}`. Responses are XADDed the same way to the matching result stream.

Monitor audio downloads support direct-origin routing without changing stream payloads:

| Variable | Default | Description |
|---|---|---|
| `AUDIO_CDN_HOST` | empty | Exact source hostname to rewrite; empty disables routing |
| `AUDIO_ORIGIN_BASE_URL` | empty | Absolute HTTP(S) object-storage base URL; empty disables routing |
| `AUDIO_ORIGIN_FALLBACK_TO_CDN` | `TRUE` | Retry original source URL after origin failure |

When configured for `cdn-broadcast.ozenx.io` and
`https://hel1.your-objectstorage.com/broadcast`, path and query are preserved. URL logs omit query
parameters. Both `panako.monitor.request` and `panako.monitor.refine.request` use this shared
download path.

### `panako-store-requests` — store by URL

```json
{
  "audio_url": "https://example.com/USRC17607839.mp3",
  "filename": "USRC17607839.mp3",
  "title": "Artist:Track",
  "request_id": "scrape-12345"
}
```

Possible response statuses on `panako-store-results` (mirror the HTTP `/store/url` endpoint):

- `ok` — indexed successfully
- `already_exists` — hash duplicate (same filename was already indexed)
- `already_indexed_match` — content duplicate (≥99% fingerprint match), upload skipped
- `skipped_empty` — 0-byte download, upload skipped
- `error` — see `error` field

### `panako-store-patch` — modify metadata

Patch operations dispatch by the `action` field. Supported actions: `rename`, `delete`.

#### `action: "rename"` — change the `path` column for an existing row without re-fingerprinting

```json
{
  "action": "rename",
  "identifier": 1612789453,
  "new_path": "RUAGT2342680.m4a",
  "request_id": "rename-RUAGT2342680"
}
```

or by `old_path`:

```json
{
  "action": "rename",
  "old_path": "/tmp/1000067653.mp3",
  "new_path": "RUAGT2342680.m4a",
  "request_id": "rename-RUAGT2342680"
}
```

Response on `panako-store-patch-results`:

```json
{
  "status": "renamed",
  "action": "rename",
  "request_id": "rename-RUAGT2342680",
  "identifier": 1612789453,
  "new_path": "RUAGT2342680.m4a"
}
```

Statuses:
- `renamed` — row found, new path written
- `rename_not_found` — no metadata row exists for the resolved `resource_id`
- `error` — see `error` field (missing `new_path`, missing identifier source, etc.)

#### `action: "delete"` — remove fingerprints and metadata for a resource_id

Async equivalent of `POST /api/v1/delete/by_id`. Useful when the workflow already produces patch requests for the same `resource_id`.

```json
{
  "action": "delete",
  "identifier": 1979144137,
  "request_id": "del-FR59R2638511"
}
```

or by `path`:

```json
{
  "action": "delete",
  "path": "/tmp/FR59R2638511.m4a",
  "request_id": "del-FR59R2638511"
}
```

Response on `panako-store-patch-results`:

```json
{
  "status": "deleted",
  "action": "delete",
  "request_id": "del-FR59R2638511",
  "identifier": 1979144137,
  "rows_deleted": 13762
}
```

Statuses:
- `deleted` — issued; `rows_deleted` is the combined fingerprint + metadata row count that existed prior to the call
- `delete_failed` — backend rejected the delete (e.g. LMDB storage active — only ClickHouse is supported)
- `error` — see `error` field (missing identifier source, etc.)

Override stream names via env: `REDIS_PATCH_REQUEST_STREAM`, `REDIS_PATCH_RESULT_STREAM`.

## JVM Flag

`--add-opens=java.base/java.nio=ALL-UNNAMED` is required for LMDB to function. It is already included in the Dockerfile `ENTRYPOINT`.
