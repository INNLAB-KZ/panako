# Refine capacity reassignment — 14 October 2026

## Requested change and deployed counts

User requested one additional refine for the separate project on Redis 10.0.0.4, then conversion of six of the 26 Main Stage-1 workers into Main refine. Existing hosts had no additional 4.5 GiB slot after reserving 2 GiB for the OS, so one existing Main refine slot was reassigned to the separate project. Total container count stays unchanged.

| Redis | Stage-1 before → after | Refine before → after |
| --- | ---: | ---: |
| 10.0.0.2 | 26 → **20** | 24 → **29** |
| 10.0.0.4 | 1 → **1** | 1 → **2** |
| **Total** | **21** | **31** |

Fleet audit across eleven worker hosts confirms **52 running monitor containers**, exact expected roles/Redis endpoints, unchanged JAR SHA-256 `7a24e3f7e2d1be928211c1b335a09c16a39d58d5e05f61b7cb76cdafb839298b`, two windows and 4608 MiB memory per container. No active-container restart or OOM flag was found. The suspect VPS 167.233.53.95 remains isolated.

## Placement and task safety

| Host | Change | Healthy UTC |
| --- | --- | --- |
| 46.224.159.198 | Four Stage-1 → four Main refine | 17:00:08 |
| 91.99.52.184 | One Stage-1 → Main refine; one Main refine → separate-project refine | 16:58:10 |
| 157.180.26.0 | One Stage-1 → Main refine | 16:58:19 |

Add five hours for Almaty local time. New Compose project: `panako-refine-rebalance-20261014`. New isolated service: `panako-refine-rebalance-20261014-refine-isolated-1` on 91.99.52.184. Existing isolated Stage-1/refine on 167.233.61.228 continue unchanged.

Each selected Stage-1 reached pending=0, was frozen for longer than its blocking Redis read, and was checked again before its idle shutdown. The selected Main refine used cooperative drain and a graceful stop after pending=0. All seven retirement receipts show zero pending ownership. Original containers and exact configuration backups were retained; their final Compose overrides were persisted at replicas=0 with restart disabled.

No manual request/result publication, ACK, reclaim, message deletion or group-cursor change was used. Workers consume existing tasks normally. Original unrelated container IDs and running states were verified on all three changed hosts.

## Configuration and resources

All new services query private ClickHouse Main 10.0.1.2:8123. Main refine clones each host's existing Main refine template. The isolated refine clones the existing isolated project's settings, including its different MONITOR_GAP_MIN_SECONDS. Both retain 3584 MiB heap, 4608 MiB container limit, two windows and one Redis worker thread. New consumer hostnames are unique. No host port is published.

Application, Java, libjvm, ffmpeg, libc and loader hashes were checked when preparing the isolated worker's local image. The image is pinned by content ID; no application rebuild or algorithm change occurred. Reserved container RAM remains 18 GiB on 46.224.159.198 and 91.99.52.184, and 27 GiB on 157.180.26.0. The first preparation on 157.180.26.0 stopped at an explicit command check because its template uses SERVER_PORT=8094; this known port was added to the allowed inputs before any mutation on that host.

Main sample at 17:00:32 UTC: CPU **34.33%**, I/O wait **0%**, available RAM **116.17 GiB**, physical NIC TX **5.061 Gbit/s**, RX **54.46 Mbit/s**. No interface errors/drops in the ten-second sample. No ClickHouse query exceptions in the prior fifteen minutes; both local instances have zero replication queue/delay, all four replicas active. These are bounded samples, not a peak-capacity guarantee.

## Real-task acceptance

**Passed at 17:04:05 UTC / 22:04:05 Almaty.** All seven new workers completed real refine requests with successful responses matched to observed request IDs and local completion times. No monitored worker error, heartbeat/ownership warning, restart or OOM was observed. The bounded observer exited automatically after acceptance.

| Host / new service | Redis | First successful processing |
| --- | --- | ---: |
| 46.224.159.198 / refine-main-1 | 10.0.0.2 | 2:26 |
| 46.224.159.198 / refine-main-2 | 10.0.0.2 | 1:37 |
| 46.224.159.198 / refine-main-3 | 10.0.0.2 | 1:26 |
| 46.224.159.198 / refine-main-4 | 10.0.0.2 | 3:41 |
| 91.99.52.184 / refine-main-1 | 10.0.0.2 | 3:38 |
| 91.99.52.184 / refine-isolated-1 | 10.0.0.4 | **4:51** |
| 157.180.26.0 / refine-main-1 | 10.0.0.2 | 5:07 |

Source audio durations are 3587–3718 seconds. Refine processes requested segments, so these timings must not be interpreted as full-hour Stage-1 benchmarks. The new isolated worker's first result was `1791997387794-0`, recording `01a12b44-6d28-7a63-8239-7c9e7c11c8f7`; it was published by the worker normally.

At acceptance, Main Stage-1 had **zero waiting tasks and five in progress**. Main refine had 29 in progress; `lag=null` is retained Redis group metadata after earlier deletion and is not treated as an empty queue. Separate-project refine had two in progress and **203 waiting**, down from 207 at preflight. These bounded observations confirm startup and real work, not a guarantee for later peaks after reducing Stage-1 capacity.

## Evidence and rollback

Private remote directory: `/home/bekzat/rebalance-20261014`; local evidence: `build/rebalance-20261014`. Contains exact before/after configurations, runtime hashes, deployment and retirement receipts, eleven-host fleet audit, Main resource sample and acceptance observations. Configuration backups contain credentials; do not commit them.

[Procedure and rollback](../rebalance-20261014/README.md). Rollback requires draining replacements first, freeing the corresponding memory slots, restoring saved overrides and restarting retained predecessors. Never restart an old project alongside its replacements. No speculative return of the isolated VPS is part of this change.
