# Promote the near-replica refine pilot

## Decision

The existing pilot on 91.99.52.184 replaces one of five shared-service refine containers on 157.180.26.0. Both query private replica 10.0.0.12. No additional worker or memory reservation is added. Fleet will return from 29 to 28 live refine consumers once the source is retired.

Fresh pilot check at 05:18:41 UTC: running, zero restarts/OOM/selected processing errors. Last two-hour log tail contains 29 completions, mean 222666 ms, maximum 382278 ms. These are mixed segment workloads. Existing settings remain two windows, heap 3584 MiB, limit 4608 MiB, CPU quota 3, validated JAR 60a75e63.

## Source retirement

Remote procedure `/home/bekzat/refine-near-replica-20261009/retire-source.py` snapshots Compose and live containers privately. It waits up to ten minutes for a shared-service consumer with zero pending requests, pauses and checks again, then stops that idle container and sets restart policy to no. Compose replica count changes from five to four; the separate canary remains running. The original container and backups are retained. No manual Redis mutations or data deletion.

## Live inventory before retirement

| Host | Refine | Route |
|---|---:|---|
| 157.180.26.0 | 6, planned 5 | 10.0.0.12 |
| 91.99.52.184 | 4 | 1 to 10.0.0.12, 3 to Main |
| 46.224.159.198 | 3 | Main |
| 167.233.105.218 | 4 | Main |
| 62.238.0.200 | 6 | 10.0.0.22 |
| 167.233.62.118 | 6 | 10.0.0.10 |

All inspected live refine containers report zero restarts. Source-retirement wait started 05:19:36 UTC, PID 3998236. Completion is not yet confirmed.

## Completed retirement

The idle-only wait was stopped after several minutes because consumers continuously acquired queued tasks. No source was retired by that procedure. Switched to the previously used bounded Docker stop with tracked normal reclaim.

At 05:27:37 UTC (10:27 Almaty), stopped `project-panako-api-10`, ID `f2628b6643ff991c114c92b7668d3a3447c849c8f0a6e4724739a07d24ca3ccd`, with restart policy no and timeout 30 seconds. Compose shared replica count is now four; separate canary remains running. All five other source-host containers remained running. The stopped container and backups are retained.

One pending request `1790883396704-0` is awaiting normal reclaim. Read-only recovery observer PID 4026440 tracks its final successful response for up to 40 minutes. Retirement is complete; recovery is not yet confirmed. Fleet is now 28 live refine consumers: 5 on 157.180.26.0, 4 on 91.99.52.184, 3 on 46.224.159.198, 4 on 167.233.105.218, 6 on 62.238.0.200 and 6 on 167.233.62.118.

Main resource snapshot during this operation: about 117 GiB available RAM; ClickHouse roughly 13.3 CPU cores of 48. This is a point-in-time observation, not a network or latency benchmark.
