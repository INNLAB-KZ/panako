# Live refine pilot near replica 10.0.0.12

## Placement

Reuse the existing R1 overlap pilot on 91.99.52.184, container `refine-r1-move-20261007-refine-from-main-1`, port 8093. Route changes from Main 10.0.1.2 to replica 10.0.0.12. This supersedes the pilot's previous purpose of replacing a consumer on 62.238.0.200; that original source remains running. No source on 157.180.26.0 is retired during this comparison.

Fleet stays at 29 running refine containers including one overlap pilot; 91.99.52.184 stays at four. CPU quota 3, heap 3584 MiB, memory limit 4608 MiB, two windows and one Redis worker thread remain unchanged.

The replica host has 12 CPUs, roughly 22.9 GiB RAM and an 18 GiB ClickHouse limit. Adding a 4.5 GiB refine there would leave insufficient configured memory headroom. Existing worker hosts have four 4.5 GiB workers each. Reusing the already allocated R1 slot avoids increasing reservations.

## Checks

- Three pings from pilot host to replica: mean 3.052 ms, no loss.
- Live environments on the pilot and updated 157.180.26.0 consumer differ only in `CLICKHOUSE_HOST` and `OLAF_CLICKHOUSE_URL`.
- Source environment is copied privately; recognition configuration remains identical.
- Validated JAR SHA256 `60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e` is not rebuilt.
- GC logging is enabled through `JAVA_TOOL_OPTIONS` in addition to the existing heap setting. The local ACK-race patch is not included, preserving the same processing artifact for this placement test.
- Bounded drain 180 seconds, then stop timeout 30 seconds. Pending tasks are tracked through read-only Redis inspection and normal application reclaim.
- Private backup, proposal, deployment status and logs: `/home/bekzat/refine-near-replica-20261008`.
- Observer records per-container CPU usage/throttling counters, cgroup memory, GC pauses, completions, errors, host network counters and available RAM for 30–40 minutes. It also tracks successful recovery of interrupted tasks.

## Status

Drain started 2026-10-08 14:04:20 UTC. Deployment passed health, environment, resource and JAR checks at 14:07:26 UTC (19:07 Almaty). Other running containers remained unchanged. New container ID `dab8a084bf4451e73e802dbc6fda5f8246d440d99c737a3f37229f3eacfd9e58`.

One interrupted request `1790881843274-0` remains tracked for successful recovery. Background observer PID 2468428 is writing samples: first 31-second sample healthy, zero restarts/OOM/selected errors, host available memory 10.38 GiB. First completed tasks are still pending. Thirty-minute checkpoint approximately 19:38 Almaty; recovery observation may run to 19:48.

Live queued tasks differ across hosts, so averages alone do not establish a controlled speedup; the earlier same-audio offline replay remains the quality comparison.

## Follow-up 2026-10-08 16:51 UTC

Observer completed at 14:37:31 UTC with passed=true and zero alerts. Ten mixed refine tasks averaged 149776.7 ms (2:30), maximum 342254 ms (5:42). These are not duration- or segment-coverage-filtered hourly results. Interrupted request recovered successfully in 382983 ms and left the pending list.

Over 1806 seconds: average container CPU 1.74 cores, cumulative throttled time 42.27 seconds, peak cgroup memory 3.88 GiB, GC pause total 280.94 seconds, maximum pause 377.9 ms. Host minimum available memory 8.12 GiB. Private interface enp7s0 averaged RX 516.17 Mbit/s and TX 2.91 Mbit/s with no recorded errors/drops; this is aggregate traffic for all four containers on the host.

Live inspect at 16:51:48 UTC confirmed the pilot running, zero restarts and no OOM. Subsequent direct SSH attempts timed out; a Main SSH attempt was reset before authentication. Later log/duration verification and internal reachability could not complete, so no fresh hourly SLA claim is made. The cause of SSH failures is undetermined.
