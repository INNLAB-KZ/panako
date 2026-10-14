# Separate project expanded to five refine — 14 October 2026

User clarified Redis 10.0.0.4 must have five refine workers. Three more Main refine slots on 46.224.159.198 were reassigned after cooperative drain. All three old requests finished before graceful shutdown; retirement receipts have pending=0. Existing Main refine instance 1 retained its ID and running state.

## Verified fleet counts

| Redis | Stage-1 | Refine |
| --- | ---: | ---: |
| 10.0.0.2 | 20 | 26 |
| 10.0.0.4 | 1 | 5 |
| Total | 21 | 31 |

Eleven-host audit confirms 52 monitor containers. The five isolated refine are distributed as follows:

- 167.233.61.228: original isolated refine, one container.
- 91.99.52.184: isolated refine from the earlier role reassignment, one container.
- 46.224.159.198: new project `panako-isolated-refine-five-20261014`, three containers.

New workers became healthy at **17:37:24 UTC / 22:37:24 Almaty**. They use Redis 10.0.0.4, private ClickHouse Main 10.0.1.2, three CPUs, 3584 MiB heap, 4608 MiB container memory, two windows and one Redis execution thread. Complete environment matches the existing isolated project's template, including its recognition settings. No host ports are published.

The local image is pinned by content ID. JAR, Java, libjvm, ffmpeg, loader and libc hashes match the existing isolated worker. JAR remains `7a24e3f7e2d1be928211c1b335a09c16a39d58d5e05f61b7cb76cdafb839298b`. No application rebuild occurred. Host memory reservation remains 18 GiB; combined CPU quotas are 11.5 of 12 CPUs.

Old Main Compose replica count is persisted as one; new isolated Compose replica count is three. The two existing isolated workers and all Stage-1 workers were left unchanged. Original containers and configuration backups remain available. No manual Redis request/result, ACK, reclaim, deletion or cursor changes occurred.

## Real-task acceptance

**Passed at 17:42:58 UTC / 22:42:58 Almaty.** All three additional workers completed real requests, with matching observed request IDs, successful Redis responses and local completion times. Processing: instance 1 **1:27** for 1186.9-second source audio; instance 2 **5:20** for 3631.1-second audio; instance 3 **3:58** for 3590.1-second audio. Refine processes requested segments, so these are not full-hour Stage-1 benchmarks.

No monitored failure, restart, OOM, heartbeat or ownership warning occurred. At acceptance all five isolated refine had tasks in progress, with 180 waiting. Isolated Stage-1 had no waiting or pending tasks. The bounded observer exited automatically; continuous monitoring is not implied.

## Evidence and rollback

[Procedure and rollback](../isolated-refine-five-20261014/README.md). Local private evidence: `build/isolated-refine-five-20261014/`; remote directory on 46.224.159.198: `/home/bekzat/isolated-refine-five-20261014`. Includes deployment/retirement receipts, exact configuration backups, runtime hashes, fleet audit and acceptance snapshots.

While this deployment ran, the user requested a storage queue status check. Its read-only findings are separate: [storage queue diagnosis](2026-10-14-storage-queue-status.md). Storage consumers were not modified as part of the refine change.
