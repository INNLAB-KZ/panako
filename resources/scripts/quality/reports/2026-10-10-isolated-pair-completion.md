# Complete processing and verify the isolated Redis pair

User explicitly requested autonomous completion without waiting for more commands. Deployment already consists of one Stage-1 and one refine on 167.233.61.228, Redis 10.0.0.4, ClickHouse Main 10.0.1.2. Preserve this one-worker-per-role configuration.

Initial audit at 05:35:35 UTC / 10:35:35 Almaty:

- Stage-1 request stream: 42 entries for 39 distinct recording IDs (three repeated submissions); 18 successful responses, one in progress, 23 waiting.
- Refine: four requests, four successful responses, empty request queue.
- Both response streams have an `orchestrator` consumer group, zero pending/lag; all published responses successfully delivered through the group cursor.
- No worker errors, OOM or restarts. Both health endpoints and Main credentialed SELECT checks pass.

Read-only observer: `/home/bekzat/isolated-pair-20261009/finish-watch.py`, evidence root `/home/bekzat/isolated-pair-finish-20261010` on 167.233.61.228. Runs every 30 seconds, bounded to six hours. Captures request/response metadata incrementally, matches distinct response entries for repeated recording IDs, tracks both request groups and both response groups, worker health/errors and host CPU/RAM/private-network counters. No manual Redis writes, request injection, ACK, claim, deletion or queue trimming.

Completion requires: successful responses for each observed request; both request groups have pending=0 and lag=0; both response groups have pending=0 and lag=0; no worker/health/resource alerts; this state remains stable for at least 60 seconds. New incoming requests are included during observation. Full-hour processing statistics require audio duration 3300–3900 seconds; refine additionally requires at least 3300 requested segment seconds. Processing timers exclude queue wait and download.

Observer restarted once immediately after startup to account for repeated recording IDs with distinct response matching. No worker restart or runtime configuration change.

Outcome: deployment and queue completion verified. Combined Stage-1 + refine processing target of 8–10 minutes remains unmet; see final results below.

## Observations during processing

- Full-hour recording `01a12241-0ebc-7cc7-94bf-98ceea7dff19` (3659.9 s): Stage-1 408737 ms, refine 388707 ms, combined 797444 ms (13:17), excluding queue wait and download. Combined 8–10 minute target was not met for this record. No recognition settings changed.
- A 15-second CPU sample showed Stage-1 averaging 2.35 cores and refine 2.27 cores under their 3-core limits; throttled CPU time was 0.46 s and 0.07 s respectively. CPU quota was not a major restriction in this sample.
- At 06:12:26.778 UTC, Stage-1 logged `Redis task ownership lost` for request `1791608866272-0`, recording `01a122b3-89ec-7484-b25c-0cc6d6d390a7`. Its successful response `1791612746646-0` was published 132 ms earlier; a read-only check found no pending request and the orchestrator response group at lag=0/pending=0. Consistent with the known heartbeat/ACK race; no lost result for this request.
- Observer now retains resolved ownership warnings separately. A warning is resolved only with a distinct successful response for that request, no pending entry and one group consumer. Unknown or pending requests still block acceptance. Original observation start time and history are preserved across observer restarts. Worker processes/configuration remain unchanged.

## Final results — 2026-10-10

Queue acceptance completed at **11:25:11 UTC / 16:25:11 Almaty** after at least 60 seconds with all four groups drained. Final live verification at **11:33:41 UTC / 16:33:41 Almaty** confirmed healthy services and drained response groups. New arrivals during observation were included.

| Check | Stage-1 | Refine |
| --- | ---: | ---: |
| Request entries | 54 | 62 |
| Distinct recording IDs | 51 | 33 |
| Matched successful response entries | 54 | 62 |
| Request lag / pending | 0 / 0 | 0 / 0 |
| Orchestrator response lag / pending | 0 / 0 | 0 / 0 |
| Container restarts / OOM | 0 / false | 0 / false |
| Health endpoint / credentialed Main SELECT 1 | pass / pass | pass / pass |

Counts are stream requests, including repeat submissions, not distinct recordings. Each request was matched to its own later response entry; repeated recording IDs did not reuse response entries. Orchestrator stream delivery/acknowledgement is verified; downstream application database persistence was not inspected.

Exactly one Stage-1 and one refine remain running on **167.233.61.228**, connected to **Redis 10.0.0.4:6379** and **ClickHouse Main 10.0.1.2:8123**. Each retains 3 CPU, 3584 MiB heap, 4608 MiB container memory, two windows and one Redis execution thread. Existing seven services on this host retain their original container IDs and old Redis 10.0.0.2. No worker configuration, algorithm, JAR or recognition settings changed during this verification.

### Processing time

Near-hour audio: 3300–3900 seconds. Refine samples additionally require at least 3300 requested segment seconds. Timers exclude queue wait and download.

| Stage | Successful samples | Mean | Maximum |
| --- | ---: | ---: | ---: |
| Stage-1 | 37 | 5:32 | 7:09 |
| Refine | 47 | 5:32 | 7:12 |

For 24 full-hour recording IDs with both stages available, summing the latest successful Stage-1 and latest successful full-hour refine gives **10:59.6 mean**, **14:13.6 maximum**, with **18/24 above 10 minutes**. These are diagnostic sums by recording ID, not traced end-to-end durations: some recordings were submitted repeatedly. Queue wait and download add further latency. The earlier 13:17 example used the then-current refine response; later repeat refinement changes the latest-response sum for that recording.

**Deployment/queue completion is done. The combined 8–10 minute processing target is not achieved.** Increasing CPU quota is not supported by the small quota sample above; further latency work requires profiling each stage and its DB queries under load.

### Warnings and resource evidence

- Full available container logs since 05:35 UTC show no Redis processing/read-loop/heartbeat failures, failed ACK warnings, actual OOM, window failures or socket timeouts.
- Two `Redis task ownership lost` warnings occurred. Both have distinct successful responses and no remaining pending entry. Stage-1 warning documented above. Refine warning at 09:13:22.362 UTC concerned request `1791621711095-0`, recording `01a12353-d724-7413-b555-2cafed01e3c9`; successful response `1791623602291-0` was published approximately 71 ms earlier. Both are consistent with the known heartbeat/ACK race; no result loss observed. This race was not patched in deployed JAR during this task.
- Across 479 resource snapshots: host CPU sample mean 41.87%, maximum 83.69%; available RAM minimum 8.8 GiB; private RX maximum 894.14 Mbit/s. Observed interface errors/drops: zero. These are worker-host metrics, not Main-server utilization.
- Resource observation has a gap from 06:45:45 to 08:29:25 UTC after the SSH-bound observer exited. The observer was restarted detached with a protected log file. Final full stream scan, full available worker logs and container restart counters cover completion evidence; no claim of continuous resource sampling during that gap.
- Observer exited automatically after acceptance. No extra monitoring process remains. Worker services stay running normally.

### Evidence

Local: `build/isolated-pair-20261009/finish-20261010/` contains `watch.json`, `records.json`, `history.jsonl`, `summary.json`, `verification.json`, `final-check.json`.

Remote: `/home/bekzat/isolated-pair-finish-20261010/` on 167.233.61.228. Metadata excludes Redis passwords, database credentials and audio URLs. No manual Redis queue writes were performed.
