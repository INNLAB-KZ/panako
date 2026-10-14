# Input-handling fleet rollout — September 25, 2026

## Gate and scope

The single-worker input pilot passed its 30-minute observation: six successful
hourly recordings; recognition mean 4:27, maximum 6:48. Five complete task-service
upper bounds averaged 4:31, maximum 7:16. No observed errors, restarts, OOMs, or
TCP retransmissions. See [pilot report](2026-09-25-input-pilot.md).

The user authorized continuation. The remaining 21 stage1 containers are selected
for sequential host batches; the existing successful pilot remains running.

| Order | Host | Containers to replace | Existing version |
|---|---|---:|---|
| 0 | 167.233.61.250 | 5 | Redis dispatch pilot |
| 1 | 167.233.53.95 | 6 | Redis dispatch pilot |
| 2 | 167.233.52.194 | 6 | Redis dispatch pilot |
| 3 | 167.233.61.228 | 4 | Production 351ba5b |

Target image: `local/ozen-panako:input-pilot-20260925-5d50209b`.
Target JAR SHA-256:
`5d50209bf724eec120623c22e282b68de62c8b46fabd4712eed01615808db022`.

Each host passed image-checksum, replica-count, current-state, and merged Compose
configuration checks. Redis and ClickHouse routes, including audio-origin routing,
were checked against live container environments. This rollout preserves those
routes and does not migrate workers to MAIN. Store and HTTP services are excluded.

## Execution and limits

- Capture current pending tasks and wait for their ACK before stopping each old
  worker. A task admitted at the handoff remains in Redis for retry.
- Recreate only the selected Compose service, preserving replica count and
  verifying that unrelated container IDs remain unchanged.
- Keep two windows, one Redis worker, 3.5 GiB heap, 4.5 GiB container memory,
  ExitOnOutOfMemoryError, and Kafka disabled for these stage1 workers.
- Observe each updated service for 30 minutes before releasing the next host.
- Enforce the corrected container-namespace network gate, task-service timing
  bounds, successful responses, and absence of errors/restarts/hidden concurrency.
- A failed gate stops further hosts. A detected fatal process failure triggers a
  guarded rollback of that service. Slow tasks and input errors do not trigger an
  unconditional rollback of a running service.

The observations are host batches, not proof of unlimited MAIN capacity or
recognition equivalence across every possible input. They keep the original
8-minute average and 10-minute maximum hourly service criteria, including
conservative sampling bounds. Invalid input or insufficient samples also stop
expansion for review.

## Operations

Detached controllers run under `/home/bekzat/input-rollout-20260925/` on each
host. The run identifier is `20260925-input-5d50209b`; controller results use
separate expiring Redis operational keys, not recognition streams.

- `status.json`: controller phase.
- `deployment.json`: current-task handoff and container identities.
- `watch.json`, `samples.jsonl`, `network.jsonl`: observation evidence.
- `gate-result.json`: gate decision.
- `overnight-summary.json` on 167.233.61.250: cumulative decisions.
- `rollback.py`: guarded rollback of that host's selected service.

The additional Compose override is
`/home/bekzat/project/docker-compose.input-fix-20260925.yml`. Future Compose
operations must include it after the files listed in that host's `settings.json`
and preserve its recorded replica count. Earlier rollout paths remain historical
evidence; their backups predate this version.

No completion of the remaining hosts is claimed until their deployment and gate
artifacts report it. MAIN benchmarking remains a separate subsequent step.

## First-host checkpoint — 15:19 UTC / 20:19 Almaty

On 167.233.61.250, all five selected containers were replaced and passed health,
configuration, and JAR checksum checks at 15:19:09 UTC. Unrelated container IDs
were unchanged. The existing memory pilot retains its original ID and new image;
all six stage1 workers on this host now run the input fix.

Initial observation shows zero restarts, OOMs, or monitored errors. Network
collection uses managed container namespaces. The 30-minute observation began at
15:19:09 UTC; its earliest decision is approximately 15:49 UTC / 20:49 Almaty.
This startup checkpoint is not a completed performance gate.

Controllers on 167.233.53.95, 167.233.52.194, and 167.233.61.228 were confirmed
alive and waiting for their predecessor. Their 16 workers remain pending rollout.

## Completed gates and mixed-version incident

Host 167.233.61.250 passed at 15:49 UTC: 25 hourly responses, recognition
mean 315.759 s, maximum 468.996 s. Twenty complete task-service upper bounds
averaged 339.034 s, maximum 513.441 s.

Host 167.233.53.95 deployed six workers but failed its gate at 16:24 UTC:
one worker reported Redis task ownership loss. Its 31 hourly responses averaged
308.215 s, maximum 482.885 s; timing was not the blocking condition. Both later
hosts correctly stopped without deployment. Twelve of 22 workers now have the
input fix, including the original pilot.

Incident evidence for request `1790349872200-0`, recording
`01a0d916-c17e-788c-86c9-665bb8ae55e3`:

- New worker on 53.95 held the request with delivery count 3 at 16:04 UTC;
  sampled heartbeat idle remained below 30 seconds.
- Legacy worker `project-panako-api-6` on 61.228 logged completion at
  16:05:11.432 UTC with processing time 342744 ms. The matching Redis response
  arrived at 16:05:11.430 UTC with that exact processing time.
- New worker began reporting ownership loss at 16:05:37 UTC and published a
  second successful response at 16:09:21 UTC, processing time 307133 ms.
  Both responses reported duration 3612 s and eight unique tracks; this does
  not establish full match equivalence.
- Legacy code performs unconditional XACK. New code checks ownership before
  ACK. Logs and response timing support a legacy completion removing a request
  already reclaimed by the new worker. No Redis command audit was available.
- At investigation, the request still existed in the stream but was no longer
  pending. No manual ACK or requeue was performed.

No further ownership-loss, window-failure, or monitor-failure messages were
found on the inspected stage1 services from 16:10 UTC through approximately
18:05 UTC; containers had zero restarts and OOMKilled=false.

## Fresh observation and continuation

A separate run, `20260925-input-resume-5d50209b`, re-observes the six existing
workers on 53.95 for 30 minutes, without replacing them. Original failed gate
and logs remain intact. Gate thresholds are unchanged. Only a passing fresh
gate releases 52.194 (six workers), then 61.228 (four workers), each with its
own deployment and observation. Mixed-version overlap remains possible until
the last legacy workers are replaced; another incident stops the chain.

Artifacts and controllers: `/home/bekzat/input-resume-20260925/` on these three
hosts. Cumulative `overnight-summary.json` is on 53.95. The existing Compose
override name is retained; Redis and ClickHouse routes remain unchanged.
MAIN migration has not started.

## Recovery checkpoint — 18:37 UTC / 23:37 Almaty

The fresh 53.95 gate passed at 18:35:28 UTC. Forty responses were observed,
including 39 hourly recordings: recognition mean 275.835 s, p95 487.401 s,
maximum 563.643 s. Some exceeded eight minutes; none in this hourly sample
exceeded ten. The 33 complete hourly task-service upper bounds averaged
286.927 s, maximum 496.690 s; this is a different subset from recognition
timings. No monitored errors, ownership loss, OOM, restarts, or container TCP
retransmissions were observed during this interval.

52.194 automatically began deployment at 18:35:30 UTC. At 18:37 UTC, four old
workers had stopped and two awaited current-task ACK; no deployment error.
61.228 remained waiting for the next gate. Twelve workers have the input fix;
the next six are being replaced. The next 30-minute observation starts only
after those six pass startup checks.

## Fleet completion — September 26, 00:50 Almaty

The recovery chain completed successfully at 19:50:11 UTC on September 25
(00:50:11 Almaty on September 26). Both remaining host gates passed:

| Host | Hourly responses | Recognition mean | Recognition maximum | Complete task-service maximum upper bound |
|---|---:|---:|---:|---:|
| 167.233.52.194 | 30 | 303.868 s | 509.698 s | 536.980 s (24 samples) |
| 167.233.61.228 | 20 | 300.913 s | 559.778 s | 580.831 s (16 samples) |

Both gates warned that some recordings exceeded eight minutes; neither failed
the ten-minute ceiling. TCP retransmission ratios were 0.000002172 and zero,
respectively. All required error, ownership, health, and concurrency checks passed.

Live inspection at approximately 19:52 UTC confirmed all 22 stage1 workers on
the four hosts running the input-fix image, zero container restarts, and
OOMKilled=false. This completes the worker rollout, not MAIN migration.
The preceding measurements used the existing ClickHouse replicas. Next:
controlled MAIN pilot with comparable recordings and measured query/network load.
