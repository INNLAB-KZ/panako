# Fleet latency release — 2026-10-12

## Final deployment result

All **56 monitor containers (27 Stage-1, 29 refine)** run candidate JAR `7a24e3f7e2d1be928211c1b335a09c16a39d58d5e05f61b7cb76cdafb839298b`. The last rollout wave passed at **11:44:58 UTC / 16:44:58 Almaty**. Live inventory at 11:51 UTC confirms the artifact and preserved configuration on every worker. All 56 are running and unpaused, with no restarts, OOM or fatal worker/window errors. All six .52.194 containers subsequently contributed attributed successful Stage-1 results; the initial sparse-traffic exception is no longer a coverage gap.

Every container retains **two windows, 3584-MiB JVM heap, 4608-MiB Docker memory, one Redis execution thread**. Existing CPU limits, service identities, mounts and routing were preserved. Redis 10.0.0.2 serves 54 containers (26 Stage-1 + 28 refine); the isolated Redis 10.0.0.4 pair remains one Stage-1 + one refine.

| Host | Stage-1 | Refine |
|---|---:|---:|
| 157.180.26.0 | 0 | 5 |
| 167.233.105.218 | 0 | 4 |
| 167.233.105.74 | 4 | 0 |
| 167.233.52.194 | 6 | 0 |
| 167.233.53.95 | 6 | 0 |
| 167.233.61.228 | 5 | 1 |
| 167.233.61.250 | 6 | 0 |
| 167.233.62.118 | 0 | 6 |
| 46.224.159.198 | 0 | 3 |
| 62.238.0.200 | 0 | 6 |
| 91.99.52.184 | 0 | 4 |

### Observed performance

Candidate response cohort: main-queue observations through approximately 11:47 UTC, isolated-pair read at 11:51 UTC. Only successfully attributed responses are counted, with manual recoveries excluded. Near-hour audio means 3300–3900 seconds; refine also requires at least 3300 requested segment seconds. This is production sampling across staggered deployment times, not a fixed-load benchmark or an exhaustive SLA audit.

| Measure | Samples | Mean | p95 | Maximum |
|---|---:|---:|---:|---:|
| Stage-1 recognition processing | 392 | 267360 ms (4:27) | 381717 ms (6:22) | 457093 ms (7:37) |
| Stage-1 Redis request → response, including download/wait | 392 | 284991 ms (4:45) | 406189 ms (6:46) | 658458 ms (10:58) |
| Refine processing, near-hour requested coverage | 234 | 264653 ms (4:25) | 379036 ms (6:19) | 544395 ms (9:04) |

Across all durations, 409 successful Stage-1 requests and 454 successful refine requests were attributed. One Stage-1 request exceeded ten minutes end to end because of repeated file-download attempts; recognition itself took 6:21. Details below. Thus Stage-1 processing gates passed, but a universal end-to-end ten-minute guarantee is not supported.

**Final audit exception:** one additional refine request returned `Download failed: Read timed out`: recording `01a0c89b-22ab-7528-b136-115265e6e015`, original request `1790885676183-0`, error response `1791802630102-0`, on 167.233.105.218. Origin and CDN retries exhausted before recognition. The original request is no longer pending. The strict zero-error audit correctly records `passed: false`; deployment completion must not be described as an error-free result. Isolated recovery completed at **11:58:17 UTC**: the same 3624.2-second audio and all 3624 requested segment seconds processed successfully in **231511 ms (3:52)**, with zero recognized tracks. All original segment boundaries were verified as preserved. Response SHA-256 `4b842e6b18075b78a9ff5963b1ce5ab74264d32b9694d110b300aadd3c8a03dd`; audio SHA-256 `cd2cbfab68a063ada27c8c9c0755ba8124b2cb58fc804eb229fb8ebfeea98476`. Both temporary recovery containers were removed. The result is saved separately and excluded from production-performance statistics. The user subsequently explicitly authorized this one publication. At **18:10:52 UTC / 23:10:52 Almaty**, the exact verified response was appended once to `panako.monitor.refine.response` on Redis `10.0.0.2`, ID **`1791828652096-0`**. Preflight verified the original request hash, absence of its pending entry, the latest error response and absence of a newer successful response. WATCH/MULTI/EXEC guarded against concurrent stream changes. At **18:11:17 UTC**, the stored response hash matched; the orchestrator group had advanced beyond that ID, with this response absent from pending and total response-group lag/pending zero. This verifies stream acknowledgment, not a separate inspection of application database/UI state. No request-stream entry was added, claimed, deleted or manually acknowledged. Evidence: `build/latency-20261012/fleet/recovery-publication-final.json`. The earlier error remains part of the historical audit; it is not erased or relabeled as an error-free run.

### ClickHouse and network

Normal read routing: **49 workers toward Main**, including one Stage-1 through `ch-read-router:18123` whose primary is `10.0.1.2:8123` and whose two external replicas are backup targets. The other seven refine workers retain their external routes: six to `10.0.0.10:8123`, one to `10.0.0.12:8123`. No route was silently moved. Main's second ClickHouse instance on 8125 remains running, with no fingerprint query traffic in the sampled final window.

Across **278 Main samples**, 07:01–11:46 UTC: maximum sampled CPU **52.82%**, maximum sampled TX **7467.95 Mbit/s**, minimum available RAM **115.57 GiB**. These are sampled intervals, not an assertion about every instantaneous burst. Last sample: CPU 27.31%, TX 3823.6 Mbit/s, RX 30.12 Mbit/s, available RAM 116.13 GiB, iowait 0%, interface error/drop deltas zero. Fingerprint SELECT p95 by client was 200–390 ms in the last 15-minute query window; individual maxima reached 4267 ms. Last-window query errors were empty.

Final external replica checks at approximately 11:42 UTC: `.12` CPU 6.93%, private TX 179.13 Mbit/s, query p95 424 ms; `.10` CPU 30.99%, private TX 702.14 Mbit/s, query p95 356 ms. Both external replicas and both Main ClickHouse instances report replication queue/delay zero and four of four active replicas. These are observed traffic rates, not measured VPS maximum link capacities.

### Observation cleanup and remaining queue work

The coordinator exited after the last verified wave. All eleven temporary read-only fleet observers stopped cleanly at 11:47 UTC; the Main sampler also stopped. Their final snapshots are deliberately frozen and matched to `observer-stop.json`, while final Docker inventory was collected live afterward. No old-consumer pending entries attributable to this release remain. No deployment-helper/probe containers or watcher processes were active in the 11:53 lifecycle check; the subsequent isolated download recovery completed and its two containers were also verified removed at 11:59 UTC. Protected rollback plans and images are retained.

At **11:54 UTC**, Stage-1 request lag is **0**, with four active pending requests and no pending request idle for more than an hour. Refine request lag remains **242103**, with 28 active pending requests and none idle for more than an hour. Both response groups have lag/pending zero. The older orphan seen earlier is absent from the current PEL. Read-only follow-up found two successful responses for its recording: `1791797798214-0` (293912 ms) and `1791801459107-0` (261634 ms); these establish successful processing, not exactly-once execution. The large historical refine backlog remains separate work; this release does not clear it. No manual changes were made to Redis 10.0.0.2 during rollout; afterward the one explicitly authorized recovery response described above was published.

Evidence: protected `build/latency-20261012/fleet/{rollout,final-audit,lifecycle-final,observer-stop,queues-final,main-final,replicas-final}.json`, per-host final inventories/observations, and `main-samples.jsonl`. Historical phases and the recovered deployment failure are retained below.

## Authorization and scope

User requested autonomous completion of the proposed stability check, download investigation and gradual worker rollout. The Stage-1 target is 8–10 minutes for a one-hour recording, excluding the later refine handoff. No additional approval is needed for the bounded deployments and their rollback.

Target artifact: `7a24e3f7e2d1be928211c1b335a09c16a39d58d5e05f61b7cb76cdafb839298b`. It changes only the two tested Olaf classes described in the isolated-pair report. Existing recognition settings, Redis routing, ClickHouse routing, memory limits and parallelism are preserved. Store containers are excluded.

## Initial follow-up

At 06:21 UTC, the isolated pair had completed eight near-hour Stage-1 tasks since deployment: 223337–340871 ms, all successful. Sixteen refine tasks succeeded, thirteen covering at least 3300 requested seconds. No OOM, restarts or new error responses. The two manually published baseline recoveries are excluded from candidate performance statistics.

Read-only inventory identified 54 existing monitor workers with the baseline `60a75e63…` JAR and two isolated candidate workers. Two store containers retain a different JAR and are outside this rollout. Last-four-hour worker logs show no `Redis monitor failed`, `Redis worker error`, OOM or failed-window exceptions. Some refine downloads retried or fell back to CDN successfully. Two old refine workers have ownership-loss warnings, to be reviewed separately from download failures.

Main snapshot: CPU 37.07%, available memory 116.07 GiB, physical network TX 5291.84 Mbit/s, RX 55.89 Mbit/s, no new interface faults or query exceptions.

External replica baseline at 07:21 UTC: 10.0.0.12 CPU 5.78%, available memory 20.47 GiB, private-interface TX 139.78 Mbit/s; 10.0.0.10 CPU 21.47%, available memory 21.0 GiB, private-interface TX 545.1 Mbit/s. No interface faults. Both have zero replication queue/delay and no query exceptions. Fingerprint-query p95 over 15 minutes: 398 ms and 349 ms, respectively. These are observed traffic rates, not claims about guaranteed VPS link capacity.

## Additional canary

The single `project/panako-api-memory` Stage-1 service on 167.233.61.250 was updated after its pending work drained. Healthy at 06:31:48 UTC. Environment, container resource limits, routing and unrelated container IDs were verified unchanged. First attributed real task: 3701-second audio, 327474 ms processing (5:27), successful; response matched the new container's completion log.

After the first additional canary succeeded, extend the controlled cohort to the four existing Stage-1 consumers on 167.233.105.74. This brings the candidate share of the original Stage-1 fleet to five of 26. It reduces the wait for representative production samples while retaining a limited rollout.

Before broader rollout, collect at least 20 distinct successful near-hour Stage-1 recordings across the isolated pair and these additional canaries, with p95 <=480000 ms and no task above 600000 ms. Each of the five main-queue candidate containers must have an attributed successful near-hour response. Attribute responses through observed pending ownership plus matching filename hash and processing duration in the candidate container's log. Do not count manual recoveries or old-image responses. Old consumers must have no pending entries before advancing.

## Download investigation

The deployed downloader already retries each source three times with 2/5-second backoff and rejects empty or Content-Length-mismatched responses. The router retries via CDN after origin attempts are exhausted. New tests simulate an early EOF, successful retry replacing partial bytes, three failed origin attempts followed by successful CDN download, and both sources failing with partial-file cleanup. All 15 download tests passed. No production downloader changes were made without evidence of a defect.

The original external transfer interruption has not been reproduced. Current retries/fallbacks recover the observed temporary errors; this is not proof that external network faults have been eliminated.

A later check measured the full Stage-1 Redis request-to-response interval, including download/wait time, separately from `processing_time_ms`. Among 257 attributed main-queue near-hour tasks: mean 273685 ms, p95 393268 ms, maximum 658458 ms. One task exceeded ten minutes end to end: recording `01a12f2f-d58b-7e4e-b7f3-674db2f54410` on .61.250, request `1791793642394-0`, response `1791794300852-0`. Recognition took 381485 ms (6:21); total 658458 ms (10:58). Logs show three failed origin attempts, then a failed first CDN attempt, followed by successful CDN retry at 08:31:53 UTC, with 59187448 bytes downloaded. The result completed successfully at 08:38:20. The generic logged cause is IOException; the exact HTTP status/network cause is not available in those logs. This is evidence of download latency, not ClickHouse overload. A universal sub-ten-minute request-to-response claim is therefore not supported, even though processing-time acceptance passed.

Two recent ownership warnings were checked against Redis: requests `1790885575463-0` and `1790885566112-0` have successful responses `1791781371377-0` (228624 ms) and `1791779234740-0` (171121 ms), respectively. Both responses precede the warning timestamp by milliseconds, and neither request remains pending. No manual ACK/claim was used. This is consistent with the previously observed completion/heartbeat race; it is not evidence of lost results.

## Deployment controls

`fleet-release.py prepare` reconstructs the exact candidate JAR from the exact baseline and a 12-KiB overlay, checks its full SHA-256, builds a child of the existing local image, validates effective Compose/environment, and retains private rollback evidence. This avoids uploading unrelated workspace changes. Some Compose versions add empty resources/placement maps when replicas are specified; only those empty maps are normalized during comparison.

`fleet-release.py deploy PROJECT SERVICE` waits for all selected consumers to have zero pending work, pauses each idle consumer, rechecks ownership, then recreates only that service at its existing count. No active task is forcibly terminated on a drain timeout. All request-stream operations are read-only. Health, exact artifact, environment, resource limits and unrelated services are checked afterward.

Rollback on the affected host:

```sh
python3 /home/bekzat/latency-fleet-20261012/fleet-release.py rollback PROJECT SERVICE
```

Original image IDs, Compose files and container specifications are saved per service under that directory. Post-mutation deployment failures attempt rollback automatically. Rollout observation is read-only and records results under `observation.json`.

All 11 target hosts and 23 service groups have prepared artifacts and rollback plans. A sequential coordinator waits for the canary gate. Before each service, it checks Main headroom (CPU <85%, TX <8.5 Gbit/s, available memory >8 GiB, no interface faults/query exceptions). After recreation it requires attributed successful responses from up to three distinct new containers, at least two minutes of observation, no worker errors and no old pending entries. Stage-1 near-hour processing must stay within the 8-minute p95 / 10-minute maximum gates. Failure holds further rollout for review.

The four-container expanded canary became healthy at 06:58:30 UTC. All four original consumers had zero pending entries after recreation.

## Canary gate passed

At **07:35:19 UTC / 12:35:19 Almaty**, the gate passed on 20 distinct near-hour Stage-1 recordings: ten from the isolated pair and ten attributed to the five main-queue candidate containers. Every candidate container contributed a successful near-hour response. Mean **300471 ms (5:00)**, p95 **356246 ms (5:56)**, maximum **390756 ms (6:31)**. No error responses, worker exceptions, restarts, OOM or old-consumer pending entries. These are observed-sample metrics, not a long-term SLA guarantee.

Sequential rollout then started with the four `project/panako-api` Stage-1 containers on 167.233.61.228. The already-verified canary services are skipped by the rollout coordinator.

## Positive-match refine regression check

An additional offline comparison used recording `01a0df55-cf39-7e8a-974e-7beb2bd987b7`, request `1790880200771-0`, with four requested segments totaling **582 seconds**. This is a positive-match quality check, not a full-hour latency sample. Exact original and candidate JARs processed the same downloaded bytes and the same recognition settings; Redis/Kafka credentials were removed and consumers disabled. No result was published.

Both runs found **one track**. The complete normalized response, including scores, time boundaries and waveform, matched; only processing time and unordered dictionary-list ordering were excluded. Baseline 41369 ms, candidate 37674 ms. These sequential timings are not an SLA or general speedup claim. Audio SHA-256: `6125ebe419e663d5dc785bb1b79abf73a5c4e72af8b3968a5ebefe595e0302ad`. Completed 08:00:50 UTC; both diagnostic containers exited successfully. Evidence: `positive-refine-status.json` and the protected baseline/candidate response files.

## Rollout progress

First general wave, 167.233.61.228: four containers healthy at 07:39:17 UTC, all prior consumers pending=0. Three attributed hour recordings completed in 315191, 220879 and 302279 ms. Verified at 07:47:51 UTC. The next five `project/panako-api` containers on 167.233.61.250 became healthy at 07:49:37 and passed observation at 08:11:36 UTC. Existing isolated-pair services and store services remain running.

The Main gate temporarily held the following wave because it detected one Code 394 (`QUERY_WAS_CANCELLED`) event. Inspection identified an `Optimize` query from `127.0.0.1`, started 07:58:24 UTC and cancelled at 08:04:41 after 376601 ms, with zero rows read/returned. This was not a worker SELECT failure. The unchanged gate waited for its 15-minute error window to clear; deployment resumed at 08:20:25 UTC. Six Stage-1 containers on 167.233.53.95 became healthy around 08:24 UTC and entered production observation.

The .53.95 wave passed with five distinct containers returning successful near-hour results. The last six Stage-1 containers on 167.233.52.194 became healthy around 08:47 UTC after a longer idle drain: consumers repeatedly acquired new work between completions. No active recording was interrupted. All 27 Stage-1 containers, including the isolated pair's worker, are now on the candidate artifact.

An explicit acceptance adjustment was made for the final .52.194 wave because incoming work became sparse: one attributed 3712-second recording completed in 220039 ms, followed by more than ten minutes of healthy observation with zero old pending entries. All six containers passed exact artifact/environment/resource and health checks, but production completions from all six are not claimed. At the same time the five updated main-queue Stage-1 hosts had 137 attributed successful near-hour recordings: mean 250840 ms, p95 381485 ms, maximum 437428 ms, no errors/restarts/OOM. The final wave's prepared manifest records the one-container / 600-second acceptance adjustment and its reason; other wave requirements are unchanged. The coordinator resumed the refine rollout around 09:03 UTC.

The first continuously busy refine exposed the limitation of polling for idle every two seconds. Its first idle-wait attempt was intentionally canceled before any artifact or service recreation; all original containers remained running and unpaused. The drain implementation now reads the compact XPENDING owner summary, polls at 20-ms intervals while seeking idle, and freezes through the local Docker Engine socket to avoid CLI startup delay. It still waits for any already-blocked XREADGROUP to settle, rechecks zero pending, and resumes immediately if a task was assigned. A disposable, network-disabled sleep container passed pause/unpause checks (22-ms observed pause round trip) and was removed afterward. No production Redis writes or recognition-code changes were introduced by this operational adjustment.

Fast polling also could not reliably catch the gap in the continuously backlogged refine queue. That second idle-only attempt was canceled without replacing or interrupting the consumer. A deployment-only Java helper was then tested locally and in a disposable Linux/JRE container: active work and heartbeats continue, the current task finishes, new work stops, and both stopped and in-flight resume paths work without overlap or interruption. Both tests completed three tasks / 240 heartbeat ticks. Test fixtures were removed. Agent JAR SHA-256: `7fb564fca397860a5d6decfcbfe17cda9f4a403d03e48513ed927fc1976ff4a9`. The helper contains no application classes and performs no class redefinition. It only sets the existing volatile consumer `running` flag, allowing `processNext` to finish result publication and ACK normally; it does not call `stop()`, close the Redis pool or interrupt processing. Failure before container replacement resumes the old consumer. The new production container starts normally without this helper loaded.

The helper uses the Java 17 [Attach API](https://docs.oracle.com/en/java/javase/17/docs/api/jdk.attach/com/sun/tools/attach/VirtualMachine.html) and [Instrumentation module-access API](https://docs.oracle.com/en/java/javase/17/docs/api/java.instrument/java/lang/instrument/Instrumentation.html). Its temporary launcher has no network, a read-only helper directory, 256-MiB memory / one-CPU limits, host PID visibility and SYS_PTRACE only to attach to the validated Docker process. The JDK image is pinned to `eclipse-temurin@sha256:43f4431cc895d37ceb115e2e1f160545ec45caee9c1cb4246fc6bb879d58aeda`. Initial disposable Docker tests exposed attach socket/mount namespace mismatches; the final host-PID-based test passed before any production attach.

The first production cooperative drain completed normally, but candidate creation then failed because its prepared image ID was no longer present. Several preparations with the same parent had reused one tag. Automatic rollback completed at 09:31:37 UTC; the original JAR, complete environment values, command, entrypoint, resources and HTTP health were checked. The affected active request `1790885652080-0` / recording `01a0cdee-3e5c-7b96-8812-ec66b4ac6b17` produced successful response `1791797480215-0` in 83949 ms and has no pending entry. This is a short refine task, not an hour-performance sample.

All 17 refine service images were subsequently rebuilt or revalidated under a unique persistent tag per service, with the exact candidate JAR hash checked by a network-disabled checksum container. Deployment now verifies image availability before draining. Failed-attempt and rollback evidence was archived, the current restored container identity was validated for retry, and the rollout resumed. The read-only observer also accounts for intentionally reused fixed consumer hostnames: pending work owned by a live replacement is not mislabeled as work stranded on a retired container. The release records zero pending before recreation separately from pending after the replacement begins consuming.

## Existing refine queue, separate from release acceptance

At 08:34 UTC, the main Redis refine group reported lag **243493**, 29 pending entries, and response-group lag/pending zero. A later read-only PEL check found 28 recently renewed entries and one old entry: request `1790883489347-0`, recording `01a0cdf0-3a40-7dd9-9816-b605c020b3af`, consumer `897ac5a646b8-1-1`, idle approximately 250270 seconds. That consumer belongs to the temporary placement canceled on October 9; it is not a container replaced by this release. No manual ACK, claim, requeue or deletion was performed. Four-hour logs of all three original refine containers on .198 contain no Redis read-loop errors. Completing this release does not mean that the historical refine backlog has been processed or this older pending entry resolved.
