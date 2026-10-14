# Remaining Main refine migration — 2026-10-06

## Scope

Move six remaining refine-role containers from Main (116.202.112.101) to
four hosts. No ClickHouse replica stopped. No manual Redis ACK, claim,
requeue or deletion. Existing unrelated containers retain their IDs.

| Main source service | Destination | New project | CPU quota | HTTP port |
| --- | --- | --- | --- | --- |
| panako-refine-a | 91.99.52.184 | refine-a-move-20261006 | 3 | 8091 |
| panako-refine-b | 167.233.105.218 | refine-b-move-20261006 | 3 | 8091 |
| panako-refine-c | 46.224.159.198 | refine-c-move-20261006 | 2.5 | 8091 |
| panako-refine-d | 91.99.52.184 | refine-d-move-20261006 | 3 | 8092 |
| panako-refine-e | 46.224.159.198 | refine-e-move-20261006 | 2.5 | 8092 |
| panako-store-h (refine role) | 62.238.0.200 | refine-h-move-20261006 | 3 | 8091 |

Each destination: 2 windows, one Redis execution slot, 3584 MiB JVM heap,
4608 MiB container memory; JDBC points to Main 10.0.1.2:8123.
Image `local/ozen-panako:refine-jdbc-20261004-60a75e63`.
JAR SHA256 `60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e`.

Recognition environment preserved per source, including differing monitor gap
settings. Allowed environment differences: JAVA_TOOL_OPTIONS, JAVA_OPTS,
KAFKA_ENABLED (FALSE), CLICKHOUSE_HOST, OLAF_CLICKHOUSE_URL. JDBC database,
credentials and query properties preserved. Unique consumer hostname per copy.
Existing validated artifact includes previous impulse/OOM and JDBC timeout fixes;
this operation does not change matching thresholds or compile new Java code.

## Deployment method

Private configuration and backup directories on destination:
`/home/bekzat/refine-{key}-move-20261006/`.
Main source backups: `/home/bekzat/refine-{key}-disable-20261006/`.
Local private artifacts: `build/refine-migration-20261006/private/`.
Do not publish these configurations; they include credentials.

Start verifies effective env, image JAR digest, memory/CPU limits, health and
unrelated container IDs. Source disable requires at least two completed tasks,
no sampled operational errors/restarts/OOM, healthy HTTP and at least 4 GiB
host memory available. It revalidates source/container identity, fresh gate,
current Compose SHA and an effective Compose diff restricted to restart=no and
replicas=0. Sources disabled sequentially because they share an override file.

Source stops use a 30-second Docker stop timeout. This is not a pending-zero
drain; any interrupted message depends on normal application reclaim/retry.
No exactly-once or duplicate-cleanup claim is made.

Thirty-minute read-only observers run separately on destinations, checking
errors, restarts, completions, host CPU/RAM and NIC counters every 15 seconds.
Two-task startup gate does not establish long-term throughput or recognition
quality across the full catalogue. Observer results remain pending until their
individual observation windows finish.

## Interim evidence

At 12:43:44 UTC source b stopped after replacement completed 52.190s and
251.382s tasks. At 12:49:04 UTC source a stopped after 159.784s and 152.778s
tasks. Both gates passed with no errors/restarts; other Main container IDs unchanged.

At approximately 12:50 UTC, Main 10-second sample: CPU 35.4%, iowait 0%,
103.9 GiB available, physical ens23 RX 6.0 / TX 1359.1 Mbps, no error/drop
counter increments. Do not add VLAN and parent NIC throughput together.
Four old refine sources were still running during this sample.

Stage-1 last-hour response sample: 66 responses, all ok; 61 with duration
3300–3900 seconds, mean 312.19 seconds, max 425.202 seconds, none over
480 or 600 seconds. These are Stage-1 times, excluding refine queue wait.
Response counts are not an audited unique-recording count.

## Accepted source retirements

| Source | Stopped at UTC | First two task durations |
| --- | --- | --- |
| a | 2026-10-06T12:49:04.917591+00:00 | 159.784s, 152.778s |
| b | 2026-10-06T12:43:44.497829+00:00 | 52.190s, 251.382s |
| c | 2026-10-06T12:57:14.822616+00:00 | 309.151s, 258.163s |
| d | 2026-10-06T12:55:14.673649+00:00 | 224.904s, 208.506s |
| e | 2026-10-06T12:58:32.822506+00:00 | 265.061s, 277.131s |

All five source retirements passed configuration and unrelated-container checks.

## H migration held for performance

The first H task contained one segment spanning 0–3600 seconds. It completed
successfully in **685.848 seconds (11m26s)**, exceeding the requested 8–10 minute
target. No OOM, restart, socket timeout or ownership-loss error was observed.
The source on Main remains running and its Compose configuration is unchanged.
Automatic source retirement was stopped before touching H.

Destination 62.238.0.200 → Main private IP ping: mean 34.992ms, 0/5 loss.
91.99.52.184 → Main: mean 2.778ms, 0/5 loss.
Alternative 157.180.26.0 → Main: mean 34.784ms, 0/5 loss.
62.238.0.200 → its existing replica 10.0.0.22: mean 23.701ms, 0/5 loss.
These are measured network delays, not verified datacenter locations.

Main query-log sample over 15 minutes: H host 10.0.0.6 averaged 278ms/query
(max 2148ms, average result 5.2MiB); new near-Main hosts .23/.21/.22 averaged
85/90/88ms (average results 4.7/4.7/5.0MiB). Query mixes differ: this supports
investigating the network path but does not isolate latency as the only cause.
H's original environment also lacks MONITOR_GAP_MIN_SECONDS=99999, leaving
the existing additional gap scan enabled. That setting was deliberately retained
for recognition quality. No gap scan was disabled to force a faster result.

At 13:00 UTC H destination: CPU 65.7%, 10.2GiB RAM available, private NIC
RX665.3Mbps, no error/drop increments in 10 seconds. No demonstrated CPU/RAM
exhaustion. Stop of the additional H pilot was requested with a bounded
pending-zero drain (up to 10 minutes), without manual Redis mutations.
The original H continues serving on Main. Final stop outcome recorded below.

Post-migration Stage-1 check: 26/26 running, zero restart counters and OOM flags;
bounded last-30-minute logs contain no selected operational errors, 29 completions.
This is a bounded log check, not proof of all historical task outcomes.

## Final-load sample after five accepted moves

At 13:08:02 UTC Main: CPU 30.9%, iowait 0%, 113.5GiB available.
Physical ens23 RX18.1 / TX3012.7Mbps; no error/drop increments over 10 seconds.
ClickHouse Docker snapshot: 1522.63% CPU (~15.2 logical CPUs), 16.71GiB RAM.
Only `project-panako-store-h-1` remains running with refine role on Main.
All five retired sources are stopped with restart=no; ClickHouse, clickhouse2,
Keeper and HAProxy remain running. Four actual store-role containers remain.

At 13:07:49 UTC: Stage-1 lag0/pending10; refine lag289140/pending31.
Compared with 12:35:49 baseline: refine lag fell206 messages in32.01 minutes;
entries-added +7, entries-read +213, pending +2. This includes temporary
source/destination overlap during migration and is **not steady-state throughput**.
No drain ETA is inferred. The backlog still contains approximately289k messages,
not an audited count of unique recordings.

Latest last-hour Stage-1 response sample: 77 results, all status=ok;72 responses
with audio duration3300–3900s: mean300.65s (5m01s), max425.202s (7m05s),
none above480/600s. This excludes time waiting for/refining in the second stage.

H pilot's second task completed in 619.924s (10m20s), also above10 minutes.
Its additional gap scan remains enabled; this migration did not alter recognition
settings to improve the timer. Five relocations are accepted; sixth remains held.
Next placement needs verified low latency to Main and sufficient memory reserve.

The requested pending-zero drain can miss an idle interval because the refine
queue is nonempty and the consumer immediately acquires another message.
Its explicit 10-minute fallback prevents an unbounded migration shutdown wait.

## H pilot shutdown and observation outcome

At 13:14:26 UTC the additional H pilot was stopped; its own Compose service
now has replicas=0/restart=no. The source H on Main was not stopped or edited.
The pending-zero drain timed out: pending_before_stop=1 and pending_after_stop=1.
That interrupted message is left for normal application reclaim/retry; recovery
has not yet been observed. No manual ACK/claim/delete/requeue was performed.
Other containers on the destination retained their IDs and remained running.
An H observer alert after this deliberate shutdown is expected; H was not accepted.

A observer PASS at13:13:39UTC:9 completions,no alerts.
B observer PASS at13:12:11UTC:11 completions,no alerts.
C/D/E all passed their two-task startup gates and had no errors in subsequent
samples. Their individual 30-minute observers were still running at handoff;
do not label those full-window gates PASS without reading their final results.

Outcome: **five accepted relocations, one performance hold**. Main retains one
refine-role container plus database/proxy/keeper and actual store services.
The six-container relocation objective is not fully achieved. Before moving H,
select a host with verified low Main RTT and sufficient memory, or investigate
query/gap-scan cost with quality controls; do not remove recognition passes merely
to make the duration target pass. No additional server was purchased.

## Follow-up: H hold resolved

At13:53:45UTC H was successfully relocated to167.233.105.218 after two
hourly tasks completed in334.760s and205.561s. Its unused local ClickHouse
replica was retired with data retained. Main now has zero refine containers.
See2026-10-06-refine-h-near-main.md for audit,health,remaining dependencies
and interrupted-source-task recovery observation. The earlier far-pilot failure
remains a historical failed placement,not an active Main-source hold.
