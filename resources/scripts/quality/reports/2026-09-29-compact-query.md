# Compact ClickHouse query storage candidate

Candidate only; not deployed to production workers.

The OOM reproduction retained 44.7 million Long objects, 35 million OlafHit
objects and 14.9 million HashMap nodes. SQL batches limit the IN list but the
entire window's expansion maps and returned hits were retained in memory.

Changes:
- OlafQueryHashIndex uses primitive long arrays for one near-hash to original
  mapping, replacing the boxed expansion set, reverse map and hash-list copy.
  Last insertion wins on overlapping ranges, as before. Zero and overflowed
  signed hashes are handled explicitly. No keys are dropped.
- OlafPackedHitList stores each returned hit as two longs: matched hash plus
  packed resource ID/time. Original hash belongs to the bucket. Blocks grow
  geometrically then stop at 4096 hits, avoiding huge array copies. List access
  materializes OlafHit only when consumed by the existing conversion code.
- Query batch size remains 1000. All returned duplicates, excluded-resource
  filtering, query thresholds and two-window concurrency are preserved.
- Existing SQL failure propagation remains: partial hits cleared, failure thrown.

This reduces representation overhead; it does not impose a hard upper bound on
matches or guarantee every possible recording fits 3.5 GiB. It does not silently
truncate fingerprints/hits. Primitive-index enumeration changes SQL batch order;
recognition parity must be checked because downstream ordering can matter.

19 targeted tests passed: 2 compact representation, 4 JDBC/failure propagation,
1 hit conversion, 6 window failures, 6 shared Redis dispatcher tests. Tests cover
range overlaps, latest-original mapping, duplicate hashes/rows, signed extremes,
zero, primitive-map growth, packed-block boundaries, field/order preservation,
null-release iteration, SQL batch completeness and avoided-resource filtering.

Candidate JAR SHA-256:
6d5dd3ddc6ad620e598426a46bd398ea662f5b2e641b7cc2c7611c42805a997b
Built from exact baseline 5d50209b JAR: only OlafStorageClickHouse.class replaced;
OlafQueryHashIndex.class and OlafPackedHitList.class added. Existing unrelated
local store-error handling changes excluded from the isolated artifact.
All other baseline JAR entries identical. No production image rebuilt.

Replay started 09:49:50 UTC / 14:49:50 Almaty on 167.233.105.74, private directory
/home/bekzat/compact-query-20260929. Same offending audio SHA as baseline OOM;
2 CPUs, 2 windows, 3584 MiB heap, 4.5 GiB container. RecognitionProbe runs without
HTTP/Redis/Kafka or result publication, reading MAIN. Runner aborts below 2 GiB
host available memory or after 20 minutes. Result pending.

After a successful replay only, quality.py runs baseline then candidate on the
same positive 54-minute recording (audio SHA
1dc05b7925f4905235b9bdd17c0838a27f31a16fb409877067e6a99732ce9699).
Fresh baseline uses current DB; historical 94 detections are not assumed.
It compares canonical raw detections and full JSON apart from processing time.
Each run capped at 20 minutes. No automatic production deployment follows.

## First replay did not pass

Stopped explicitly at 10:01:22 UTC after 682 seconds. Exit 143 from operator
stop; Java OOM false, Docker OOMKilled false. No complete recognition result.
MonitorHandler had cancelled windows on its existing timeout, then awaited the
unfinished worker. SIGQUIT thread dump showed that worker inside JDBC HTTP
response-header read / processQueryQueue. The loop lacked interruption checks.
This does not prove a single query was stalled: sampled MAIN active query max
elapsed was 0.188 seconds. Repeated query-loop work can outlive cancellation.
Quality comparison was blocked, so no recognition parity claim is available.

## Second candidate

Added interruption checks during expansion, between SQL batches, periodically
while reading rows, and before successful return. Cancellation clears partial
hits and leaves the thread's interrupted flag intact. No full success returned.
A blocking JDBC socket operation itself is not made interruptible by these checks.

Sorted primitive keys before forming SQL batches to group nearby hashes and
avoid random batch grouping introduced by the primitive hash-table iterator.
All hashes and last-original mappings retained. Sorting consumes one primitive
long array, not a boxed set/list. Performance and ordering impact still need
live replay/parity validation.

21 targeted tests pass, including cancellation before queries and between
batches after a returned row. Candidate JAR SHA-256:
3d26844c8bad756158a10c37b6d18432abdd96eb07f856a3d3d328915a90d8be.
Same artifact isolation: one replaced storage class plus two new helper classes.

Second replay started 10:04:00 UTC / 15:04:00 Almaty. Private directory and
container: compact-query-v2-20260929 on 167.233.105.74. Limits unchanged.
Quality runner initially raced status-file creation and exited before any
recognition work; readiness handling corrected and runner restarted. Positive
baseline/candidate comparison waits for successful offending-recording replay.
No production code/configuration deployment occurred.

## Second replay failed cleanly; compact experiment retired from active source

V2 finished 10:10:02 UTC, exit 1, no Java OOM / Docker OOMKilled. Exception:
Window processing timed out or was cancelled (TimeoutException). This is not
successful recognition. The dependent positive comparison did not run.

Subsequent offline window audit isolated the root cause to four one-LSB impulses
in silence. Active source now restores the original boxed lookup structures,
SQL batching order and ordinary hit lists, while retaining cancellation checks
and SQL error propagation. Experimental compact helpers and their test have
been moved to compact-query/experimental-source for reference; they are not
part of the next production candidate. See 2026-09-29-quantization-impulse.md.
