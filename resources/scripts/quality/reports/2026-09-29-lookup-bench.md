# Isolated lookup benchmark — September 29, 2026

User authorized profiling and a controlled acceleration comparison after the
70:08 outlier took 12:47. No production recognition configuration is changed.

Target 167.233.105.74 had 12.4 GiB available memory and negligible worker CPU
at preflight. A standalone Docker process is limited to two CPUs and 4.5 GiB
memory, with 3584 MiB Java heap and two recognition windows. No server or
Redis/Kafka consumer is started. The runner invokes deployed MonitorHandler
methods directly and writes results to private benchmark artifacts. The
ClickHouse schema initializer is disabled in the temporary storage overlay;
benchmark operations only read the existing database. Local source files
under src/ remain unchanged.

Exact production JAR verified:
5d50209bf724eec120623c22e282b68de62c8b46fabd4712eed01615808db022.
Input recording: 01a0eaae-9d47-7877-8b74-b598f424d02c, duration 4208.2 s,
63874940 bytes, SHA-256
3d980b45155fd51b2f33a44d08b8ab4f1c203c94159108a6040749803e365700.
The source URL is stored only in permission-protected private artifacts.

Temporary Java overlay measures connection, hash expansion/sorting,
executeQuery, ResultSet.next, and row mapping times separately. JDBC may
buffer results during executeQuery, so those metrics cannot perfectly isolate
network from server time; server query_log is needed for comparison.

Baseline retains current hash iteration order and batches of 1000 hashes.
Candidate sorts the same expanded hash set before forming 1000-hash batches.
Hypothesis: contiguous hash values reduce storage reads. Recognition thresholds,
window count, and fingerprint generation remain unchanged. Both runs use the
same instrumented overlay, source audio, container limits, and deployed JAR.
Queries have mode-specific comments for server-side accounting.

Runner compares canonical raw recognition results and final response JSON
excluding processing_time_ms. A matching zero-track result alone cannot
establish general recognition-quality equivalence. No automatic production
rollout occurs regardless of benchmark results. Timing is a sequential A/B
comparison on a live database with varying load/cache state, not a strict
capacity/SLA measurement. Two-CPU limits differ from production.

Started 04:01:32 UTC / 09:01:32 Almaty. Baseline then sorted candidate run
sequentially, each with a 40-minute timeout, a low host-memory abort guard,
and no restart policy. Containers exit after processing; failure/timeout stops
the active container. Directory on target: /home/bekzat/lookup-bench-20260929/.

## Baseline completed; collector repaired

Baseline recognition completed successfully in 951859 ms (15:52), duration
4208.16 s, 13 raw matches and zero final tracks. Container exited zero, no OOM.
The supervisor then failed parsing combined stdout/stderr: Java metric printf
fragments interleaved with logger messages. This was a collector failure,
not a recognition failure. Sorted run had not started.

Updated supervisor reads Docker stdout separately from stderr and can reuse
an already completed container after verifying clean exit and result files.
Recovered all 112 nonempty-window metric records from baseline stdout:
11062 SQL batches; 917110720 returned rows; cumulative times across parallel
windows: connection 0.431 s, expansion 3.249 s, executeQuery 783.230 s,
ResultSet.next 244.946 s, mapping 200.783 s, total lookup 1311.483 s.
These overlapping sums must not be interpreted as wall-clock durations.

Baseline artifacts preserved. Supervisor resumed with the sorted candidate;
no baseline replay and no production worker/configuration changes. Automatic
comparison evaluator restarted after confirming candidate was running.

## Completed comparison and MAIN snapshot — 10:27 Almaty

Sorted candidate completed 05:21:16 UTC. Recognition time 845190 ms (14:05)
versus baseline 951859 ms (15:52): observed reduction 11.21%, speedup 1.126.
Canonical 13 raw matches and final response (excluding processing time) were
identical. Both returned exactly 917110720 rows in 11062 SQL batches. No
production rollout performed; one recording and sequential live-DB runs
remain insufficient for broad quality/performance claims.

Server query_log totals baseline/sorted: read_rows 1439794916 / 1401386724;
read_bytes 22447064424 / 22014061144; cumulative server query ms 804253 / 625339.
Client executeQuery sum decreased 783.230 to 548.486 seconds; next() remained
244.946 / 244.879 seconds, mapping 200.783 / 204.111 seconds. Sorting did not
reduce returned-row volume; decoded result_bytes approximately 14.68 GB each
must not be confused with measured wire traffic.

MAIN snapshot at 05:26:53 UTC, after benchmark finished: host CPU 69.1%,
iowait 0%, available RAM 91.7 GiB; physical TX 2945.4 Mbps / RX 18.7 Mbps,
zero new interface errors/drops over ten seconds. NVMe busy 2.4–2.6%, writes
about 39.2 MiB/s per device. Separate Docker sample: primary ClickHouse
1212.99% CPU (about 12 logical cores), memory excluding cache 2.03 GiB;
clickhouse2 10.11% CPU, 942 MiB. Host has 48 logical CPUs; samples differ
in interval and must not be treated as an exact resource attribution.

MAIN five-minute fingerprint sample: 35232 queries, p95 303 ms, maximum
846 ms, zero exceptions. Active queries 14, longest 0.192 s; one merge.
Two local replicated tables had zero queue/delay and no read-only state.
This snapshot supports keeping the planned two independent replica hosts
for availability/reserve while avoiding claims that failover is already
configured or validated. Workers currently route directly to MAIN.
