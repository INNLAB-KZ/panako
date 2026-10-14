# Convert one ClickHouse replica host to stage1 — September 28, 2026

The user explicitly requested converting one replica server into stage1 workers
that query ClickHouse MAIN. Selected host: 167.233.105.74, private 10.0.0.9.
It has 12 logical CPUs, approximately 23 GiB RAM, and 346 GiB free disk.

## Dependency and capacity checks

The selected replica had no fingerprint queries in its 24-hour query-log
sample, no other active queries, and zero replication queue/delay. Running
Panako containers on all four original stage1 hosts, the three original refine
hosts, and MAIN had no direct route to this replica. The original workers
that used it were already moved to MAIN. The newer relocated refines also
use MAIN. No distributed-table/client routing is changed by this operation.

MAIN pre-expansion measurements: CPU about 59.8%, available RAM 92.3 GiB.
Fingerprint queries over five minutes: 18,974 queries, p95 245 ms, maximum
901 ms, zero exceptions. These are baseline samples, not capacity guarantees.

## Conversion

Four stage1 workers use the exact running image and environment copied from
167.233.61.250, verified by image ID and JAR SHA-256
5d50209bf724eec120623c22e282b68de62c8b46fabd4712eed01615808db022.
Each uses two windows, one Redis execution worker, 3584 MiB heap,
ExitOnOutOfMemoryError, 4608 MiB memory limit, Kafka disabled, and MAIN
10.0.1.2:8123. Default Compose bridge networking supplies distinct container
hostnames; HTTP ports are not published to the host.

The local ClickHouse container is stopped, its restart policy set to no,
and its existing Compose service set to deploy.replicas=0. Data under
/srv/clickhouse/data is retained unchanged; no replica metadata is dropped.
Effective Compose comparison verifies that only replica count changes.
The original configuration and container inspection are backed up privately.
The root-owned Compose file is written by a temporary Docker helper using
the existing ClickHouse image; original file ownership/mode are preserved.

Startup failure stops the new workers and restores ClickHouse configuration
and service. A fatal worker failure during observation invokes guarded rollback.
Nonfatal timing/error gate failures require review before further expansion.

## Monitoring and operations

Worker artifacts: /home/bekzat/stage1-convert-20260928/ on 167.233.105.74.
Compose project: stage1-main-20260928, service stage1, scale four.
status.json records waiting_image, stopping_replica, starting_workers,
observing, passed, review_required, or rollback/error states.
deployment.json records the four new identities and retained replica data.

After startup, the 30-minute timing/error gate and container TCP collector run.
MAIN query timings are sampled in main-queries.jsonl. MAIN physical/VLAN
counters are recorded under the same artifact directory on 116.202.112.101.
Review overlapping intervals without adding physical and VLAN byte counts.

If startup succeeds, stage1 total becomes 26: sixteen use MAIN and ten still
use other replicas. Five of the original six replica servers remain running
ClickHouse. Selecting the final two retained replicas and converting the
remaining servers are separate stages. No subsequent conversion is automatic.

## Startup completed — 23:35 Almaty

All four workers passed image/JAR, route, memory, and HTTP checks at
18:35:40 UTC / 23:35:40 Almaty September 28. The former ClickHouse container
exited normally with code zero. Its data directory is retained, restart policy
is no, and the existing Compose service has replicas zero. The conversion
is deployed; its performance gate remains pending.

The 30-minute observation began immediately after startup. Expected result:
approximately 00:06 Almaty September 29. Initial watcher samples show four
healthy workers, zero restarts/OOM, and no alerts. Stage1 fleet now totals
26 containers: sixteen routed to MAIN, ten to remaining replicas.

## Follow-up — September 29, approximately 00:17 Almaty

The initial 30-minute gate ended review_required solely for insufficient
completions and timing samples: no tasks completed within that interval.
No watcher alerts or TCP retransmissions were recorded.

A subsequent live check found all four HTTP health endpoints returning 200,
zero container restarts, and zero actual Java OOM errors. One worker had
completed one task in 329486 ms (5 min 29 sec); the other three had no
completions. Audio duration was not verified in this check, so this is not
an hourly-audio SLA result. Redis group lag was zero, with one pending task
fleet-wide and none on these four workers. No queue entries were changed.

MAIN fingerprint queries over the last five minutes: 19124 queries,
p95 230.45 ms, maximum 503 ms, zero exceptions. A separate ten-second live
sample measured CPU busy 48.2%, iowait 0%, physical-interface RX 1.5 Mbps,
TX 435.4 Mbps, and zero new drops/errors. Available RAM was 94945 MiB.
These samples do not establish capacity under four simultaneously busy workers.
No additional server conversion or configuration changes were made.
