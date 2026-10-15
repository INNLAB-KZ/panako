# Input-handling pilot — September 25, 2026

## Changes and verification

Two input-handling changes were prepared:

- A JSON null/non-string filename no longer consumes the next field name. The
  existing URL-based filename fallback preserves the audio extension.
- Empty downloads and responses shorter than the advertised Content-Length raise
  IOException, allowing the existing retry mechanism to retry the download.

The original duration failure has not been reproduced. A fresh download through
the configured origin produced a valid 63,996,602-byte M4A file with ffprobe
duration 3593.984 seconds. The fixes address a reproduced parser bug and a missing
download-integrity check; they do not prove the historical failure's cause.

Twenty-two focused Java tests passed: request parsing (3), download length (3),
origin routing/fallback (9), window failures (5), and match acceptance (2).

JAR comparison found changed bytecode only in `HttpUtil.class` and
`PanakoRedisStreamWorker.class`. Recognition classes are byte-identical.

- Previous SHA-256: `d556675b5076dbe09e49eeeb5da4a403725237d41021e4346322dad1f6bf66fd`.
- New SHA-256: `5d50209bf724eec120623c22e282b68de62c8b46fabd4712eed01615808db022`.
- New image: `local/ozen-panako:input-pilot-20260925-5d50209b`.

## Deployment scope

Only `project-panako-api-memory-1` on `167.233.61.250` is selected. Configuration
preflight confirmed unchanged Redis/ClickHouse routing, two windows, one Redis
worker, heap 3.5 GiB, container limit 4.5 GiB, and ExitOnOutOfMemoryError enabled.
The pilot continues to use its existing ClickHouse replica.

Deployment waits for the captured current task to leave Redis pending state,
then pauses/stops the old container. A task admitted at the handoff remains
pending and becomes eligible for retry through an owner-checked XCLAIM. No manual
ACK or new recognition request is published by the deployment script.

The protected directory on the host is `/home/bekzat/input-pilot-20260925/`.
`deployment.json` records the actual deployment outcome; `watch.json`,
`network.jsonl`, and `gate-result.json` contain the 30-minute observation and
decision. TCP measurements are collected inside the pilot's Docker network
namespace. Processing and conservative full-service timings use the same gates
as the corrected rollout.

Process failure/OOM/restart triggers a guarded return to the previous input
handling version. Slow-task or input-error observations stop approval for further
expansion; they do not automatically revert a running pilot. Rollback checks the
container identity and Compose override to avoid overwriting subsequent changes.

This pilot does not resume the remaining fleet automatically and does not migrate
requests to ClickHouse MAIN.

## Live deployment confirmation

Healthy at 13:50:24 UTC / 18:50:24 Asia/Almaty. Observation started at 13:50:25
UTC; checkpoint is **19:20:25 Asia/Almaty**. The deployment verified the new
JAR checksum and confirmed all other project container IDs were unchanged.

At 13:54:12 UTC, the pilot was healthy with zero restarts, OOMs, or logged task
errors; sampled memory was 3.301 GiB out of 4.5 GiB. One task was pending with
delivery count 1. The downloaded file retained its correct `.m4a` filename,
confirming the null-filename fallback on a live request. No completed task had
yet been observed, so processing-time and sustained-stability gates were pending.


## Completed observation: PASS

The 30-minute observation ended at 14:20:33 UTC / 19:20:33 Asia/Almaty.
All six observed responses were successful, for recordings approximately one
hour long. Mean recognition time was 266.692 seconds (4:27), maximum 407.838
seconds (6:48). For five tasks with complete observable start/end boundaries,
mean full-service upper bound was 271.374 seconds (4:31), maximum 436.061
seconds (7:16). The initially active task is excluded from the latter metric.

No observed OOM, restart, download/recognition error, heartbeat failure, ownership
loss, or TCP retransmission occurred during this observation. The gate passed
without warnings. Live inspection at 14:55:20 UTC confirmed the same candidate
container remained running, with restart count 0 and OOMKilled=false. This later
inspection does not extend the original 30-minute error/latency measurement.

These measurements use the existing ClickHouse replica. No fleet-wide rollout
of this input-handling version or MAIN migration is claimed by this result.
