# Final Main refine relocation: near-Main pilot — 2026-10-06

## Prior move verification

All five accepted earlier moves (A/B/C/D/E) passed their individual 30-minute
observers: 9/11/7/9/6 completions, respectively, with no alerts.
The stopped H pilot on62.238.0.200 no longer has pending entries under its old
consumer. This proves ownership cleared, not successful completion of that task;
the interrupted message ID was not captured in the earlier hold report.

## Placement and dependencies

Audited running Docker application ClickHouse endpoints on all14 supplied hosts.
91.99.52.184 replica (10.0.0.23) is still configured for three legacy services
on167.233.61.228: project-panako-api-http-1 and project-panako-store-{1,2}.
Do not retire that replica before migrating those dependencies.

Selected167.233.105.218 (10.0.0.21). No running application in the audited
inventory points to its replica. Main HAProxy targets localhost8123/8125;
Stage-1 router targets Main and retained replicas10.0.0.12/10.0.0.10.
No active TCP clients on candidate8123/9000/9009 at audit time. Candidate had
no active queries and no query-log rows in last24h; empty logs alone do not prove
absence of clients. No Distributed tables or cluster references to candidate
were found on the queried Main/retained/candidate ClickHouse instances.

Ping candidate→Main: 0.527/1.266/4.100ms min/mean/max over5 probes, no loss;
last four probes0.527–0.603ms. Previous far pilot mean34.992ms.

## Replica retirement

At13:43:05UTC stopped local `clickhouse` on167.233.105.218, exact ID
795e1d2d08ee753fee4b9e1fd2a89d9a58246658f8e856052d160156c87c70b2.
Persisted restart=no and deploy.replicas=0 in/srv/clickhouse/docker-compose.yml.
Validated effective Compose diff with original project-directory resolution.
Backups/manifest:/home/bekzat/ch-retire-20261006/ (private, includes secrets).
Data/config/user/log bind directories preserved; no DROP TABLE/REPLICA, volume
removal or data deletion. Existing refine container IDs/running states unchanged.

Root-owned Compose file updated using a short-lived existing ClickHouse image
with network disabled and read-only root filesystem, mounting only the target
Compose directory and read-only staged proposal; file ownership/mode preserved.
Direct sudo was unavailable. The earlier validation failure occurred before any
mutation (relative paths in proposal); project-directory resolution fixed it.

Before stop, both replicated tables were writable/session-valid, queues0,delay0,
7 active of8 registered. After stop Main,clickhouse2 and retained replicas .12/.10
remain writable/session-valid, queues0,delay0,6 active of8. This is the expected
active-count decrease. Four external replica servers remain; final two-replica
architecture is not yet complete because remaining dependencies must be handled.

## H2 pilot

Project/root:refine-h2-move-20261006 at167.233.105.218.
Container:refine-h2-move-20261006-refine-from-main-1;HTTP8092;
consumer hostname:refine-main-h2-20261006.
Main source remains project-panako-store-h-1 until the pilot passes.

Image:local/ozen-panako:refine-jdbc-20261004-60a75e63.
JAR SHA256:60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e.
Heap3584MiB/container4608MiB,2 windows,1 Redis slot,CPU quota3.
Recognition settings including H's additional gap scan preserved.
JDBC targetsMain10.0.1.2:8123. No new Java compilation or algorithm changes.
Four refine container memory limits total18GiB against22.9GiB host RAM.

Read-only30-minute resource/error observer plus40-minute Redis response watcher
started. Response watcher associates pending recording IDs and completed-task
millisecond timings with refine responses, retaining audio duration and requested
segment span. No manual Redis ACK/claim/delete/requeue occurs.
Acceptance requires startup/health/resource gate and two near-hour full-segment
results under600s before source retirement. Full observer status is recorded
separately; a short startup gate does not establish sustained SLA.

## Interim workload evidence

First accepted hourly sample:recording01a09f71-a403-77c6-b0d1-7b24fd884e9a,
request1790879756194-0,one3600s segment,audio3600s,statusok,
processing334760ms (5m35s),own completion log timing matched.
This is a different recording from the far pilot; no controlled speedup ratio
or unchanged catalogue-wide recognition quality is claimed from these timings.

Second observed request1790879759892-0,recording01a09f11-6bad-7568-aa56-fa319cf43ad0,
has2 segments totaling3581s. It is still processing at this interim checkpoint.
Host resource observer:minimum available RAM8.38GiB;CPU around70–78%,
private-network RX~512–562Mbps in sampled15s intervals,no interface error/drop
increments and no application errors/restarts. Four-container memory limits fit
with approximately4.9GiB host reserve; this does not replace runtime monitoring.

## Accepted migration

Performance gate passed at2026-10-06T13:53:22.864340+00:00.
- 01a09f71-a403-77c6-b0d1-7b24fd884e9a:audio3600.0s,segments3600.0s,processing334.760s,statusok.
- 01a09f11-6bad-7568-aa56-fa319cf43ad0:audio3594.0s,segments3581.0s,processing205.561s,statusok.

Main source stopped at2026-10-06T13:53:45.704787+00:00. Source ID and all unrelated Main container IDs verified; effective Compose changes restricted to source restart=no and deploy.replicas=0.

All six original refine moves are now accepted. Live count:28 refine containers,
all off Main:157.180.26.0=6,62.238.0.200=6,167.233.62.118=6,
91.99.52.184=3,46.224.159.198=3,167.233.105.218=4,Main=0.
No total worker-count increase: source/destination overlap ended.
Main retains four real store containers,ClickHouse,clickhouse2,Keeper,HAProxy,
and optimizer. External replicas remaining:.12,.10,.22,.23.

Source stop had one pending message before and after shutdown:
1790879760794-0,recording01a0dbef-c24a-7ccb-a1b3-4e2126fdd0c1,
old ownerch01-1-1. No manual queue mutation was made. A30-minute read-only
recovery observer tracks that exact pending message and subsequent recording
responses atMain:/home/bekzat/refine-h-disable-20261006/recovery.json.
Successful retry is not yet confirmed. Correlation of recording response with
message clearance is operational evidence; duplicate history is not audited.

At13:55UTC Main10-second sample:CPU13.9%,iowait0%,117.2GiB available,
physical NIC RX6.1/TX1367.1Mbps,no error/drop increments. ClickHouse snapshot
~6.84 logical CPU cores,16.04GiB Docker memory usage.
Destination:CPU74.4%,available9.8GiB,private NIC RX606.8/TX2.8Mbps,no errors/drops.
Main/retained-replica final SQL audit remains healthy,queues0,6 active replicas.
These are load-dependent samples,not capacity benchmarks.

Latest Stage-1 last-hour responses:168,allok;163 hourly-duration samples,
mean276.36s (4m36s),max438.008s (7m18s),none above8/10minutes.
Stage-1 lag0/pending0;refine lag288823/pending29.
Stage-1 timing excludes refine queue wait; backlog remains substantial.
H2 full30-minute observer still running at handoff; the two-hourly-result gate
passed, but no full-window PASS is claimed yet.

Final Stage-1 container check:{"workers": 26, "running": 26, "restarts": 0, "oom": 0, "errors": 0, "completions_last30min": 96}. Selected error counts cover bounded last30-minute logs.
