# Autonomous completion of the remaining legacy refine rollout

User authorized continuing through observation intervals without further commands. Scope expanded from the remaining legacy refine rollout to transport diagnosis, far-host migration to Main, direct-origin downloads and retirement of the third external replica. No manual Redis ACK/claim/delete/requeue. Recognition thresholds remain unchanged. The sections below are chronological; the final verification section supersedes earlier pending statuses.

## Host A: 62.238.0.200

Canary started 05:33:52 UTC, observer 05:34:24 UTC. First almost-hour workload: 3579 segment seconds, 500341 ms processing, successful response, matching local completion, deliveries=1. Five legacy containers remain until the 30-minute canary observation passes. Prepared wave root `/home/bekzat/refine-lease-wave-a-20261009`; controller PID 2915954 checks the canary gate, deploys the prepared group and waits for its 30–40-minute observer.

## Host B: 167.233.62.118

Original base image differs: `c8f3eade41b294ad0c12cad6b81b17197139e6244643e01c3dd0d14c710fedd3`, JAR `d14f2d55d3a9ab44360c430ae898e5fb15f23b5c6cf4e15323f1aeab85b309bd`, from September 21. Its Compose stack includes `docker-compose.audio-origin-canary.yml`; that audio-routing environment is preserved in the copied runtime environment. Candidate image copies only the validated 60a75e63 JAR onto this exact runtime base.

Canary `/home/bekzat/refine-lease-canary-b-20261009` started at 05:47:04 UTC, new container ID `14d73fb28e35f51a9e2e7b5441f7e29b43dfc952d419040c69068505353da23f`. Interrupted request `1790883401958-0` is tracked for recovery. Other five old containers remain running. Observer PIDs 397218, 397219, 397220.

Prepared wave root `/home/bekzat/refine-lease-wave-b-20261009`. Both base and audio-origin override image definitions are updated together after the gate, so future layered Compose runs retain the validated image. All remaining environment values are frozen from identical live source environments.

## Recovery from previous placement

Request `1790883396704-0`, recording `01a0c4ee-29b9-7147-9dce-a4d1572c0730`, recovered successfully by 05:41:25 UTC: processing 223605 ms and empty PEL. No manual queue operations.

## Initial controller state (superseded below)

Controllers wait for successful canary observations before any group deployment. Final completion and SLA results are pending. The local ACK-race warning fix remains unshipped; deployed JAR still 60a75e63.

## Queue baseline

At 05:53:16 UTC: refine consumer group lag 264072, entries-read 2818709, pending 29. Redis lists 367 historical consumer names; that is not a live worker count. The live fleet has 28 consumers. Queue operations remain read-only; backlog reduction must be measured independently of per-task processing time.

## Additional audit findings and authorized continuation

Stage-1: all 26 monitor consumers on five hosts have JAR 60a75e63, two windows, one Redis thread, 3584 MiB heap (JDK_JAVA_OPTIONS) and 4608 MiB container limit, zero restarts/OOM. Two legacy store consumers on 167.233.61.228 are excluded from this count.

The audit found three intermediate 5d50209b refine builds, on baseline F (91.99.52.184), E (46.224.159.198) and G (167.233.105.218). These already have earlier recognition/concurrency fixes but predate the validated 60a75e63 artifact. Updating them in existing projects with identical environments/resources/routes. Initial preflight rejected multiple Compose files before any mutation; the procedure now includes and snapshots all layers, preserving the JDBC override. Private root on each host: `/home/bekzat/refine-jdbc-tail-20261009`.

The 157.180.26.0 audit confirmed slow successful near-hour jobs: 989362 ms for recording 01a0b051-ea0a-7936-bbc7-707248109563 (3593.9 seconds audio), 915044 ms for 01a0c4d2-01b2-7b85-b145-e6964d15cf6e (3593.9 seconds). Errors and restarts were zero; the ten-minute objective remains unmet there.

A separate route pilot is prepared on the existing `refine-lease-canary-20261007-refine-canary-1` at 157.180.26.0: replica 10.0.0.12 to Main 10.0.1.2, same validated JAR, two windows, 3 CPUs, 3584 MiB heap and 4608 MiB memory. No extra container. GC/CPU/network observation enabled. Private root `/home/bekzat/refine-main-probe-20261009`. Deployment PID 4166068.

Wave A completed deployment at 06:07:52 UTC: all five replacements healthy and hash/config/resource checks passed. Four pending requests are tracked; observer PID 3022238, task observer PID 3022239. Wave B remains gated on its 30-minute canary observation. Its earlier interrupted request recovered successfully by 06:04:41 UTC (645357 ms on a legacy consumer).

## Updates through 06:35 UTC

- F/E/G replacements healthy at 06:18:11–12 UTC; all three now use 60a75e63. Existing unlimited CPU quotas were preserved. Recovery observers remain active.
- Wave B passed its initial pilot, but group deployment encountered `PermissionError` on the root-owned audio-origin Compose override after stopping five containers. The same write failed during rollback. Recovered by atomic replacement through the writable parent directory; original audio-origin environment retained. Five containers restored and verified at 06:26:51 UTC. They have no published HTTP ports; health checks now use Docker bridge addresses. Observers 544140/544141 run; controller exited during the error and requires explicit final status closure.
- All 28 refine have individually been verified on 60a75e63; final simultaneous inventory and observation are pending.
- Main route pilots healthy on 157 at 06:17:16 UTC and on 62 at 06:24:06 UTC. First completed jobs processed only 1684 and 1439 segment seconds respectively, so 418314/468604 ms are not full-hour performance evidence. No group route promotion yet.
- Wave A recovered request 1790883408013-0 ended with an error response: incomplete audio download, expected 59351395 bytes, received 8331264. PEL is empty, but this is not successful recovery. Other tracked tasks remain under observation. No manual Redis changes.
- Read-only inspection of deployed JDBC 0.9.8 bytecode confirms a default socket receive buffer request of 804800 bytes. On 157 the host cap is 212992, observed socket buffer 425984 and advertised receive window about 262912, RTT to Main about 35 ms. Testing OS autotuning with a bounded, read-only synthetic JDBC result; production routes/configuration unchanged by that test.

## Socket transport experiment

On 157, alternating default / `socket_rcvbuf=0&socket_sndbuf=0` JDBC connections to Main returned the same 2,000,000 synthetic rows and checksum `7233952834921363328` on all six runs. Default times: 5628, 4819, 4887 ms; autotuned: 914, 1125, 1606 ms. Test container used one CPU and 512 MiB, exited successfully, no queue access. This measures result transport/decoding, not full recognition quality.

At 06:37:35 UTC started an offline replay on 157 using the previous exact recording, audio size 64210427 bytes, segments `0:3233,3254:3600`, original replica 10.0.0.12, 3 CPU / 3584 MiB heap / 4608 MiB memory / two windows and validated 60a75e63 JAR. Only JDBC socket buffer parameters differ from the original environment. Same complete `matches` JSON must pass before production socket rollout. Root `/home/bekzat/refine-socket-replay-20261009`.

Fleet snapshot at 06:37:44 UTC verifies 28 actual refine consumers all on 60a75e63; temporary offline replay excluded. No OOM/restarts. Download retry SocketTimeoutException on 62 api-7 caused observer alerts; logs show a later successful audio download, so inspect individual response outcomes rather than equating that log string to a ClickHouse timeout. A second tracked request, 1790883409967-0, nevertheless received an error response and empty PEL. Failed downloads remain explicitly unresolved, not counted as successful recovery.

Offline socket replay passed: processing 457442 ms (7:37), original 717428 ms (11:57), 36.24% reduction. Full matches JSON identical, 99 raw matches, sampled peak heap 3079516160 bytes, GC pauses 73.15 s. Total probe elapsed 489.419 s includes initialization/serialization beyond the processing timer. Container exited 0 at 06:45:45 UTC. Observer initially mishandled the original response's one-element list; normalized it and finalized status without rerunning recognition.

Started socket-only production pilots on the existing Main canaries: deploy PID 87269 on 157, 3208683 on 62. Original route observers were explicitly superseded and stopped; their snapshots remain. New observers will track pending tasks across recreation. Same JAR, heap, windows, quota, route and audio source; only socket buffer URL parameters change.

Direct origin download check on 62 succeeded for recording 01a096cb-476d-7262-8a32-2e585b6006bc: 59351395 bytes, approximately eight seconds, exit 0. Used the existing 167.233.62.118 audio-origin configuration, with CDN fallback. No response publication or requeue. Second failed recording 01a09724-3bb3-747a-919c-6fd7d367d875 reported expected 64210427 bytes, received 49987584.

Wave B's five interrupted requests all recovered successfully by 06:44:52 UTC; final 30-minute observation remains active. Queue around 06:43 UTC: entries-read 2819023, lag 263783, pending 29 (lag down 289 from 05:53 baseline).

## Gates closed and live socket results

- F and G tail updates passed their 30-minute observers at 06:48:21–24 UTC, including successful recovery of interrupted requests.
- Wave B passed at 06:57:06 UTC, all five recoveries successful, no alerts. Its exited controller's status was explicitly closed as complete after inspecting that result.
- Wave A's automatic observer ended failed at 06:48 because of a download error and still-pending 1790883407464-0. The latter subsequently recovered on the socket pilot: recording 01a098f3-cab4-72a2-8450-f6f06770b8ac, 3563 processed segment seconds, audio 3619.5 s, successful response in 344813 ms (5:45). This is a full near-hour live sample. The distinct failed download remains unresolved.
- Both socket pilots healthy at 06:50:40/53 UTC. By 06:58, two successful completions each, no errors/OOM/restarts. The two 62 completions covered only 1222 and 594 seconds; they are not full-hour acceptance samples.
- Main sample at 06:55:21 UTC: CPU busy 18.28%, available RAM 116.70 GiB, physical TX 2529.78 Mbps, private VLAN TX 2415.06 Mbps (included in physical), no interface errors/drops. All five ClickHouse instances reported two healthy replicated tables, five total/active replicas, queue size 0, delay 0; no Distributed tables or cluster targets pointing at retiring .22.

## Existing heartbeat warning reviewed

157 pilot message 1790883420152-0 (recording 01a0ca10-8843-7b0d-9062-02e2c2dd80de) produced one successful response, 1791529237176-0, processing 136872 ms. All sampled leases had deliveries=1, unchanged owner, idle below 30 seconds. Completion logged 07:00:37.193746763 UTC; ownership warning 07:00:37.315028039 UTC, 121 ms later. PEL empty, no `not acknowledged` warning. This matches the locally reproduced own-ACK/heartbeat race, whose code fix is still unshipped.

The promotion controller initially blocked. After examining the exact event, a reviewed warning allowance was recorded for that single count on that single container; any new ownership warning, timeout, failure, health incident or OOM still blocks. Controller restarted as PID 151535. Promotion requires at least 15 minutes of fresh stable samples, two successful own near-hour completions (3300+ segment seconds), mean at most eight minutes, max at most ten minutes, and successful recovery of interrupted messages. The 30-minute pilot observer continues independently.

Source `OlafStorageClickHouse.getConnection()` now sets socket buffer defaults to zero for future builds; Java 17 `compileJava` passed. This source change is not in the deployed 60a75e63 JAR. Current production pilots use explicit URL parameters, preserving the validated artifact.

Second failed audio also downloaded successfully through origin: 64210427 bytes, exit 0. Neither failed request has been manually requeued or republished.

## Route group rollout

157 socket pilot full near-hour results: 344813 and 396829 ms, mean 370821 ms (6:11), max 6:37. Both interrupted requests recovered; second recovery 534153 ms on another consumer. Gate passed at 07:13:07 UTC. Four shared refine containers moved from .12 to Main, with socket autotuning and the validated direct audio-origin configuration. Healthy at 07:16:15 UTC; names api-10/api-4/api-3/api-8, ports 8351–8354. Count remains five on the host. Interrupted IDs: 1790883424564-0, 1790883424448-0, 1790883423805-0, 1790883424207-0. Root `/home/bekzat/refine-route-main-20261009`; controller phase `observing_wave` means its deployment controller exited, while watch/task observers continue. Expected 30-minute check about 07:46 UTC.

62 socket pilot full near-hour results: 419351 and 437196 ms, mean 428273.5 ms (7:08), max 7:17. Gate passed at 07:23:47 UTC after the interrupted task was recovered by the new 157 group. Five shared refine are being moved from .22 to Main with the same transport/origin settings; root `/home/bekzat/refine-route-main-20261009`, controller PID 3328527. No replica shutdown yet.

The 157 socket observer finished at 07:20:54: all recovery successful; its raw `passed=false` is solely the specifically reviewed own-ACK warning. The separate pilot's source-download setting is now being updated too, after group deployment completed: origin deploy PID 216699, root `/home/bekzat/refine-origin-canary-20261009`. Old socket task observer explicitly stopped before recreation. Equivalent origin deployment files are prepared on 62, not yet launched; wait until its group deployment is healthy to avoid conflicting container identity checks.

15-second host samples at 07:19:29 UTC: 157 CPU 52.86%, steal 0%, private RX 474.74 Mbps; 62 CPU 50.04%, steal 0%, private RX 528.99 Mbps. Interface errors/drops zero on both. Main at 07:24:22: CPU 27.02%, available RAM 116.33 GiB, physical TX 3302.98 Mbps.

Retirement proposal prepared on 46 at `/home/bekzat/ch-retire-20261009`, original Compose saved, runtime unchanged. Local scripts under `resources/scripts/quality/ch-retire-20261009`: fresh DB audit, stop-replica, and registration drop restricted to ch07. Stop gate requires healthy five-copy replication and no application table access in the preceding ten minutes. Last 24-hour .22 client audit found only 10.0.0.6 (62 host). Collect a fresh dependency/DB audit after route observers pass; do not use earlier snapshots for shutdown.

Main resource sampler PID 1847119 runs under `/home/bekzat/refine-finish-main-20261009`, 30-second samples, finite 7200-second duration. Stop it after final verification if task finishes earlier. No promise of unattended checks after the final response.


## Final verification, 08:04–08:07 UTC / 13:04–13:07 Almaty

### Deployment and recovery gates

- 62 shared group healthy at 07:26:57 UTC; its five interrupted requests recovered successfully. Thirty-minute observer passed at 07:57:02, no alerts; deployment controller explicitly marked complete.
- 157 shared group observer passed at 07:46:28, all four interrupted requests recovered, no alerts; controller marked complete.
- Direct-origin setting added to the existing 157 pilot at 07:24:53 and 62 pilot at 07:34:07. Both retain Main route, socket autotuning, validated JAR and resource limits. Their thirty-minute observers passed at 07:55:04 and 08:04:14 respectively; all interrupted requests recovered, no alerts.
- All eleven far-host refine consumers now use Main with `socket_rcvbuf=0&socket_sndbuf=0` and the existing direct-origin download configuration with CDN fallback.
- A final one-hour retained-log audit across all 28 refine found no monitor/worker failures, ownership/heartbeat warnings, unacknowledged-task warnings, OOM, socket timeout or window-processing errors. No completed task exceeded ten minutes in those retained logs. This aggregate log check does not establish full-hour coverage for every completion.

### Fleet and architecture

Fourteen-host fresh inventory: **54 monitor consumers: 26 Stage-1 and 28 refine**. Every consumer has validated JAR SHA256 `60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e`, two windows, one Redis worker thread, 3584 MiB JVM heap and 4608 MiB container memory. Docker restarts and OOM flags are zero.

| Role / route | Containers |
| --- | ---: |
| Stage-1 directly to Main 10.0.1.2 | 25 |
| Stage-1 through read router, Main primary / .12 and .10 backups | 1 |
| Refine directly to Main 10.0.1.2 | 21 |
| Refine to retained replica 10.0.0.10 | 6 |
| Refine to retained replica 10.0.0.12 | 1 |

Remaining external ClickHouse servers: **167.233.101.144 / 10.0.0.12 / ch05** and **167.233.107.246 / 10.0.0.10 / ch06**. Main retains `clickhouse` (8123/ch01), `clickhouse2` (8125/ch02), Keeper, router and store services.

### ch07 retirement

Fresh audit at 08:01:30 UTC: all five replicas healthy, both tables synchronized, no active queries or application table clients on .22 during the preceding ten minutes. Fourteen-host environment/router inventory found no references to .22 as a backend.

At 08:02:05 UTC stopped only `clickhouse` on **46.224.159.198 / 10.0.0.22**, set Docker restart policy to `no`, and persisted Compose `restart: no` / `deploy.replicas: 0`. Original Compose saved under `/home/bekzat/ch-retire-20261009`. All three refine retained identical container IDs and stayed running. Data/config/log/user mounts preserved.

At 08:02:18 removed the inactive **ch07 registration** for `default.olaf_fingerprints` and `default.olaf_metadata`, after backing up its Keeper registration children. No table data was deleted. This avoids waiting on a retired replica during synchronous mutations. Post-change audit of Main 8123/8125 and both external replicas: **4 total / 4 active**, zero delay, empty replication queues, no unfinished mutations or query errors. Registration retirement means restarting the old container alone is not a complete rollback; rejoining requires an explicit replica recovery procedure.

### Processing-time acceptance

Frozen snapshots of the four final cohorts contain **38 distinct successful recordings** with audio duration 3300–3900 seconds and at least 3300 requested segment seconds. Each processing time also matches a completion logged by the observed container:

- Mean **335986 ms (5:36)**.
- p95 **457777 ms (7:38)**.
- Maximum **464792 ms (7:45)**.
- None exceeded eight minutes.

These are refine processing times, excluding queue wait and audio download. They do not establish end-to-end Stage-1 + refine latency. The separate exact replay demonstrated identical complete matches JSON (99 raw matches), with processing reduced from 11:57 to 7:37. Recognition thresholds and matching logic were not changed by the transport/origin rollout.

### Main resources and query latency

76 thirty-second samples from 07:27:22 through 08:04:52 UTC:

- CPU mean **34.51%**, maximum **55.38%**, final **16.85%**.
- Physical NIC transmit mean **4.75 Gbit/s**, maximum **6.78 Gbit/s**, final **2.52 Gbit/s**.
- Available RAM minimum **113.58 GiB**, final **116.89 GiB**.
- Physical interface RX/TX errors and drops: **0** throughout the interval. VLAN traffic is included in physical NIC traffic and was not added again.

The interval includes the bounded offline recovery probe on Main. At the first post-retirement DB snapshot, Main completed 22129 fingerprint SELECTs in five minutes: mean **155.1 ms**, p95 **323 ms**, maximum **1961 ms**. All four instances had no query errors in their preceding ten-minute windows. These observations support available capacity at the measured workload, not a guarantee for arbitrary future concurrency.

### Queue and two earlier download failures

Read-only queue snapshot at 08:04:15 UTC:

- Stage-1: lag **0**, pending **0**.
- Refine: lag **263277**, pending **28**, entries-read **2819561**.
- Since 05:53:16 baseline: lag decreased **795**, entries-read increased **852**. The large historical backlog remains; fast processing is not the same as short queue wait.

Two requests previously acknowledged after explicit CDN download error responses were recomputed offline, using the validated JAR and the production response format. Origin audio hashes independently match the earlier downloads. No Redis/Kafka consumers ran in the probes:

| Recording | Processing | Result |
| --- | ---: | --- |
| 01a096cb-476d-7262-8a32-2e585b6006bc | 224014 ms; only 1374 segment seconds | Successful, zero unique tracks |
| 01a09724-3bb3-747a-919c-6fd7d367d875 | 358895 ms; full 3600 segment seconds | Successful, two unique tracks |

Results and hashes are saved at `/home/bekzat/refine-failed-replay-20261009` on Main. Read-only publication preflight confirmed unchanged original requests, empty PEL entries and latest responses still `error`. **Successful results have not been published to Redis.** An asynchronous clarification requests approval to append exactly these two verified responses, because the user previously deferred manual Redis work. No ACK/claim/delete/requeue was performed. Earlier failed raw observer results are retained; they are not relabeled as successful.

### Cleanup and artifact boundaries

Temporary replay/download containers exited successfully. Main resource sampler stopped after the final measurements; final cohort task observers stopped after acceptance snapshots. No unattended monitoring is promised after this session.

Production remains on the validated 60a75e63 artifact. The local heartbeat/own-ACK warning fix and source-level socket defaults are **not shipped**. Production socket tuning is applied through JDBC URL parameters. Java 17 compilation and the exact replay validated the source/configuration work; no full shadow build or broad unreviewed local-source deployment was performed.

Final machine-readable evidence: `build/refine-finish-20261009/final/` (fleet, observer snapshots, task timings, resources, queue and DB audits). Private credentials/audio manifests remain in restricted local/remote directories.


Final repeated DB audit at **08:05:57 UTC** reconfirmed all four active instances healthy, both tables 4/4, empty mutation/replication queues, zero query errors. Main's latest five-minute fingerprint sample: **20919 completed, mean 156.8 ms, p95 325 ms, maximum 5907 ms**. The isolated tail is recorded rather than hidden by the earlier 1961 ms maximum. Final Docker checks confirm all four retired external ClickHouse containers (.22, .23, .9, .21) stopped with restart policy `no` and preserved mounts. Final cohort observers on 157 had already expired; the two remaining task observers on 62 (3386306/3429992) were stopped explicitly. All four acceptance watch snapshots are finished/passed with recovery complete and no alerts.


## Approved publication completed, 08:37 UTC / 13:37 Almaty

User explicitly approved publishing the two verified recovery results. The guarded publisher revalidated the original requests, response hashes, empty request PEL entries and latest error responses, then appended exactly two successful responses to `panako.monitor.refine.response`:

| Recording | New response ID |
| --- | --- |
| 01a096cb-476d-7262-8a32-2e585b6006bc | 1791535043804-0 |
| 01a09724-3bb3-747a-919c-6fd7d367d875 | 1791535043805-0 |

Read-back at 08:37:48 UTC verified both payloads byte-for-byte against their prepared SHA256 hashes, matching recording keys and `status=ok`. The `orchestrator` group had advanced beyond both response IDs; neither entry was pending. Its total pending and lag were both zero. This verifies response-stream delivery; downstream database/UI persistence was not separately inspected.

The earlier publication hold is resolved. No request-stream mutation, manual ACK, claim, deletion or trimming was performed. Evidence saved at `build/refine-finish-20261009/final/publication-verification.json` and `/home/bekzat/refine-failed-replay-20261009/publication-verification.json` on Main.
