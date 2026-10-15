# Isolated Redis pair: latency and download audit — 2026-10-12

## Live baseline

At 01:30–01:32 UTC / 06:30–06:32 Almaty, the isolated pair on 167.233.61.228 remains healthy, connected to Redis 10.0.0.4 and Main 10.0.1.2. Request and response groups have zero lag/pending. Stage-1 entries-read=129; refine=207. Neither container restarted or OOM-killed. Original seven host services retain their container IDs and old Redis routing.

Last-24-hour near-hour audio (3300–3900 s; refine also requires >=3300 requested segment seconds):

| Role | Successful samples | Processing mean | p95 | Maximum | Request-to-response mean |
| --- | ---: | ---: | ---: | ---: | ---: |
| Stage-1 | 47 | 337.07 s | 408.20 s | 422.76 s | 431.00 s |
| Refine | 84 | 338.70 s | 428.54 s | 487.68 s | 467.44 s |

Request-to-response includes queue wait, download, response construction and publication. Repeat requests are associated with the latest preceding request of that recording ID; this is not a traced cross-stage end-to-end metric.

Main 10-second sample: CPU 37.62%, iowait 0%, available RAM 115.98 GiB, physical NIC TX 5225.12 Mbit/s / RX 56.07 Mbit/s, no new errors/drops. Both local ClickHouse instances have healthy replicated tables, queue=0, delay=0, four active replicas. No query exceptions in the preceding 15 minutes.

Main :8123 fingerprint queries from worker host 10.0.0.19 over 15 minutes: 6414 queries, mean 143.6 ms, p95 249 ms, maximum 543 ms, mean decoded result_bytes 9.53 MB. This includes all clients on that host. Result bytes are not measured wire bytes. The short snapshot does not establish peak capacity.

## Download errors

Three error responses in the last 24 hours were incomplete downloads, caught by expected-length validation:

- Stage-1 `01a1295a-d888-778d-bcb0-b3f395d79a4b`, request `1791695446209-0`: expected 57070065, received 41656320 bytes. No later successful response at audit time.
- Refine `01a1287e-e6a8-7d17-a894-f09f2a57a849`, request `1791687397234-0`: expected 56950488, received 12496896 bytes. Earlier success was for a different segment request and does not resolve this failed submission.
- Refine `01a129fa-e88c-719d-b745-6461927bedaf`: expected 59460010, received 24993792 bytes. A later request completed successfully; no new recovery needed for this one.

Configured CDN hostname matches these source URLs. Origin routing points to Helsinki object storage with CDN fallback. Stage-1 offline download succeeded from origin on its first attempt; the original truncation cause is not yet established.

## Offline profiling

Two bounded sequential diagnostic replays use the exact unresolved request bodies, same production image/JAR SHA-256 `60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e`, same recognition settings and ClickHouse endpoint. Limits: three CPUs, 3584 MiB heap, 4608 MiB memory, two windows. Queue and API credentials are removed; Redis/Kafka consumers disabled. No request injection or result publication.

Only diagnostic probe classes are compiled against the deployed JAR; no production application build or rollout. Java Flight Recorder uses profile settings; GC logs and response artifacts are protected on the worker. Resource guard requires >7 GiB available before each processing run. Download deadline 900 seconds; processing deadline 1200 seconds. Containers have no restart policy.

Evidence: `build/latency-20261012/` locally, `/home/bekzat/latency-20261012/` on worker. JFR contains captured JVM environment; keep recording files private. The summary tool emits only selected performance events, not environment or credentials.

Both original-JAR replays completed successfully, with full expected audio lengths:

| Role / failed request | Audio | Processing | Tracks | GC pause sum |
| --- | ---: | ---: | ---: | ---: |
| Stage-1 `1791695446209-0` | 3616.1 s | 316902 ms | 7 | 64.184 s |
| Refine `1791687397234-0` | 3626.1 s | 260993 ms | 0 | 46.924 s |

These are two different recordings; their times must not be added as an end-to-end result. Refine's zero-track response covers the exact two requested segments. Original application settings and deployed JAR were used. Results were initially saved separately pending approval; both were subsequently approved and published as recorded below.

Stage-1 JFR: 34015 execution samples, of which 13126 (38.6%) have `OlafStrategy.drainHits` as the first application/driver frame. Weighted allocation samples estimate 332.9 GB allocated over the run, not resident memory. Large contributors: boxed integers/longs, matches, hits, JDBC row wrappers. Socket-read events total 63.34 seconds across overlapping threads and only events above the recording threshold; this is not total network wait. GC pause sum is roughly 20% of processing time. Refine: 756 GC events, 46.924 s pause sum, weighted allocation estimate 246.8 GB. No OOM or failed replay.

## Candidate changes and checks

1. Histogram uses one boxed delta key and one `get` before `put`, preserving the original HashMap insertion operation and strict maximum comparison. Differential check: 12000 cases including ties, tree-bin collisions, negative IDs and counts outside the Integer cache.
2. `OlafMatchBuckets` caches the per-resource match list with primitive integer keys within one conversion pass. On first encounter it still invokes the original backing-map `computeIfAbsent`; later hits bypass boxing and repeated HashMap tree lookup. Backing-map iteration, list identity and hit order are retained. Differential check: 800000 hits including cache growth, collisions, signed extremes and existing/null buckets. Targeted Gradle `OlafHitConversionTest` passed, including source-hit release and duplicate preservation.

Diagnostic overlays exclude unrelated local store/metadata changes. A reconstructed baseline was compared against deployed bytecode: the only normalized difference is compiler emission of `getClass()` in storage logging (`invokeinterface` versus `Object.invokevirtual`). Candidate 1 changes only the histogram lambda relative to that reconstructed baseline. The production JAR and live workers remain unchanged during these experiments.

All offline comparisons completed:

| Variant | Stage-1 | Refine | Response comparison |
| --- | ---: | ---: | --- |
| Exact deployed JAR | 316902 ms | 260993 ms | baseline |
| Histogram only | 308964 ms | 244480 ms | identical for both recordings |
| Histogram + bucket cache | 285371 ms | 222675 ms | identical for both recordings |

The combined candidate is 9.95% faster on the Stage-1 recording and 14.68% faster on the refine recording. These are different recordings, not an end-to-end sum. Full response comparison removes only `processing_time_ms` and canonicalizes match-list order; track identities, scores, time boundaries and waveform remain identical. Stage-1 has seven tracks; refine has zero tracks, so that refine case alone cannot establish broad positive-match equivalence. Differential histogram/cache checks cover the underlying ordering and counting contracts.

Stage-1 cache candidate: 28974 execution samples versus 34015 baseline; weighted allocation estimate 301.7 GB versus 332.9 GB; GC pause sum 65.39 s versus 64.18 s. This run's benefit came mainly from reduced CPU work, not reduced pause duration. Sequential timing differences also reflect shared load, cache state and profiling overhead; no SLA claim.

## Isolated-pair rollout

Prepared immutable child images from the original role images. New JAR `7a24e3f7e2d1be928211c1b335a09c16a39d58d5e05f61b7cb76cdafb839298b` differs from the deployed artifact only in `OlafStrategy.class` and added `OlafMatchBuckets.class`; all other ZIP entry contents were verified identical. Unrelated local store and metadata changes were excluded. Candidate class hashes are saved in `artifact-hashes.json` / `release.json`.

Initial Docker build used an image ID in `FROM`; BuildKit interpreted it as a registry tag and rejected the lookup. Fixed by tagging each already-local base image and verifying its ID before build. Both images then built successfully. This preparation failure did not touch live containers.

Rollout starts at 02:09:48 UTC. It waits for zero pending tasks, pauses the old consumer and rechecks pending before recreation. A paused-container stop was checked with a disposable shell container: clean exit=0. The rollout changes one role at a time, checks health, exact JAR hash, unchanged environment and limits, and verifies the original seven services. No manual Redis ACK/claim/delete operations.

Rollback: `python3 /home/bekzat/latency-20261012/deploy-pair.py --rollback` on 167.233.61.228. Original Compose/status manifests and image IDs are retained under the protected diagnostic directory. Deployment failure triggers this rollback automatically for changed roles.

Both roles were updated successfully: Stage-1 healthy at **02:20:34 UTC**, refine at **02:20:39 UTC**. Exact artifact hashes, unchanged environment/resource limits and original seven container IDs were checked. Follow-up at 02:22:02 UTC confirmed credentialed Main SELECT 1, Redis connection, no pending requests and no OOM/restarts. The groups now retain two historical consumer entries each; only one live container per role runs. No old consumer records were manually deleted.

Acceptance observer required a successful real queue response from each new container, no worker failures/restarts/OOM, all request/response groups drained for 60 seconds, and adequate available memory. **Passed at 03:19:55 UTC / 08:19:55 Almaty.**

At 03:13:43 UTC, the new Stage-1 completed its first real 3636.8-second recording in **335291 ms (5:35)**; a second task remains active. Refine completed four real recordings in **257306–352030 ms (4:17–5:52)**. Requested refine segments cover 3314, 3425, 3457 and 3596 seconds, so all four qualify for the near-hour cohort. No new error responses, monitored worker errors, restarts or OOM kills.

Fresh Main snapshot at 03:09:11 UTC: CPU **19.34%**, iowait **0%**, available RAM **116.85 GiB**, physical NIC TX **2609.0 Mbit/s**, RX **15.38 Mbit/s**, no new interface errors/drops. Worker-host fingerprint queries over the preceding 15 minutes: mean **85 ms**, p95 **129 ms**, maximum **485 ms** (3132 queries). No query exceptions; replicated-table queues and delays remain zero. This short snapshot does not establish peak capacity or attribute the change solely to the candidate.

## Upstream handoff delay

Additional read-only timing analysis matched 44 successful Stage-1 responses to their first observed later refine request for the same recording ID. Delay from Stage-1 response publication to refine request publication: minimum **30:05.8**, median **31:06.1**, mean **31:40.9**, maximum **38:23.2**. This delay occurs before the request enters the refine stream; it is not refine queue waiting time or ClickHouse query duration. Evidence: `handoff-timing.json`.

The producer's reason/configuration was not inspected or changed. User clarified that the **8–10 minute target applies only to Stage-1**. This upstream delay is therefore outside the requested processing target.

## Final acceptance and recovery publication

| New-container live work | Samples | Mean | Minimum | Maximum |
| --- | ---: | ---: | ---: | ---: |
| Stage-1, near-hour recordings | 2 | 325.500 s (5:26 rounded) | 315.709 s | 335.291 s |
| Refine, near-hour requested coverage | 4 | 301.529 s (5:02 rounded) | 257.306 s | 352.030 s |

All six real tasks succeeded before manual recovery publication. Both Stage-1 samples meet the requested 8–10 minute processing target; this small cohort does not establish a long-term SLA. No new worker exceptions, restarts, OOM kills or ownership-loss warnings. All four request/response groups have zero lag and pending at final audit. Credentialed Main SELECT 1 and both HTTP health endpoints succeed. Available host memory: 15.66 GiB. Original seven host services retain their IDs and routing.

Each isolated service retains three CPUs, 3584 MiB heap, 4608 MiB container memory, two windows and one Redis execution thread. Environment and resource limits are unchanged. Redis is 10.0.0.4; ClickHouse is 10.0.1.2. Both use the verified candidate JAR. No diagnostic runners or diagnostic containers remain active; the acceptance observer exited after success.

User explicitly approved the two original-JAR recovery responses. Publication preflight checked exact payload hashes, original request equality, no newer requests, no pending original tasks and no newer successful response. The old refine error had left the retained response stream; the saved 01:31 audit overlaps the currently retained stream history and confirms the unresolved error. A WATCH/MULTI/EXEC guard prevents a concurrent stream change from invalidating preflight. No request entries, ACKs, claims or deletions were performed manually.

| Recovery | Recording | New response ID | Published UTC |
| --- | --- | --- | --- |
| Stage-1 | `01a1295a-d888-778d-bcb0-b3f395d79a4b` | `1791775272903-0` | 03:21:12 |
| Refine | `01a1287e-e6a8-7d17-a894-f09f2a57a849` | `1791775273591-0` | 03:21:13 |

Published payloads match the saved baseline hashes. The orchestrator response groups advanced past both IDs; neither entry remains pending, and both groups have zero lag/pending. This verifies Redis consumption and acknowledgement, not an independent downstream UI/database inspection. Evidence: `recovery-publication.json`, `recovery-acknowledgement.json`, `acceptance.json`, `final-audit.json`.
