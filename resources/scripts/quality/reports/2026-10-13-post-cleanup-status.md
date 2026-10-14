# Post-cleanup status — 2026-10-13, 22:17 Almaty

Read-only follow-up; no service changes or queue mutations.

## Workload and Main

All 56 monitor containers are running: 27 Stage-1, 29 refine. Main Redis Stage-1 has zero unread entries and five active pending tasks. In the previous hour: 174 successful Stage-1 responses, including 170 near-hour recordings; no error responses. Near-hour processing mean 245801 ms, p95 359674 ms, max 450832 ms. Request-to-response, matched to an unambiguous retained request: mean 252201 ms (4:12), p95 366192 ms (6:06), max 457567 ms (7:38), zero above ten minutes in this sample.

Refine unread count, measured directly: **23604**, versus 26874 after cleanup. Distinct unread exact requests: **15989**, versus 25200 after cleanup. Current exact duplicate excess: **7615**, versus 1674 after cleanup. This is a net change; it is not a count of all duplicates submitted since cleanup. A separate current-hour check found **135 of 224** new unread requests duplicate an earlier unread request. Refine produced **453 successful responses**, zero errors in the last hour. All 28 pending entries have idle time below an hour. Both response groups have lag and pending zero. Request-group lag remains null after authorized deletion; no cursor reset was performed.

Main ten-second host sample: CPU **26.46%**, iowait 0%, available RAM **116.52 GiB**, TX **3893.97 Mbit/s**, RX 31.74 Mbit/s, interface error/drop deltas zero. Last-15-minute fingerprint-query p95 per client **181–389 ms**, with an individual maximum of 4806 ms. No query exceptions in that window; both Main instances have replication queue/delay zero and four of four active replicas.

## Two JVM crashes require separate diagnosis

Host **167.233.53.95**:

- `project-panako-api-7`: restarted once at **14:12:15 UTC / 19:12:15 Almaty**. Docker die exit code 127. SIGSEGV in C2-compiled `java.util.TimSort.sort`, called by `OlafStrategy.lambda$query$5` in a monitor window thread.
- `project-panako-api-6`: restarted once at **14:12:45 UTC / 19:12:45 Almaty**. Docker die exit code 127. SIGSEGV in `G1ParScanThreadState::trim_queue_to_threshold`, on `GC Thread#8`.

Both use Temurin **17.0.20+8** and restarted automatically. Both are currently running; no fatal worker/window errors in last-hour log checks. Current `OOMKilled=false` does not establish historical cause. Crash reports establish fatal native/JVM faults; root cause is not yet established. Do not attribute these to heap exhaustion, a particular JDK bug, ClickHouse or host hardware without further evidence. The close timing on one host is an investigation lead, not proof of a host fault. Kernel journal access via non-interactive sudo was unavailable.

Private crash reports and crash-window logs were saved under `/home/bekzat/refine-capacity-20261012/jvm-crash-20261013/` on that host. Full reports contain potentially sensitive runtime details and are not included in this document. Safe summaries and telemetry are in `build/status-20261013/`.

The separate orchestrator task remains described in `ORCHESTRATOR_REFINE_DEDUP_PROMPT.md`. This follow-up did not modify that other project or perform another duplicate cleanup.
