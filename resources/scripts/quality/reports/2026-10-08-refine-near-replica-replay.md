# Offline near-replica replay

Recording01a09b28-d9e6-79cf-ac2b-998350d26eff (3600s), original request
1790880541992-0; exact segments0–3233 and3254–3600 (3579s total).
Historical far worker157.180.26.0→10.0.0.12 took717428ms.
New temporary probe on Main116.202.112.101 still queries replica10.0.0.12,
not Main database. Current measured RTT~0.6ms vs~25ms from far worker.

Offline Java entry point repeats deployed Redis handler segment extraction,
monitoring and absolute-time offset logic. It starts no HTTP server, Redis
consumer or result publisher. Results are files only; no duplicate requests
or results are inserted into production Redis. Ordinary storage constructor
performs existing CREATE TABLE IF NOT EXISTS startup checks.

JAR60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e,
copied source config.properties and source environment; Redis explicitly disabled.
CPU quota3, heap3584MiB, memory4608MiB, two windows; recognition thresholds retained.
Probe compiled independently against the deployed JAR with --release17; no rebuild
or replacement of production Java code. Main has>110GiB available before probe.
No production container stopped or recreated.

Main lacks the far worker's exact Docker base image. Uses existing Main image
sha256:0e044d8de525bd27c7dac82fbc182205053a85e75302861cadcde5759019a77b,
with same reported Java17.0.19+10; verified JAR loaded via read-only bind mount.
OS/image layers and physical CPU differ. Together with differing database load
and catalogue updates, this means historical comparison does not isolate RTT
causality or constitute a controlled concurrent A/B performance guarantee.

Initial preflight for absent source image failed before any workload. Python
HTTP download received403 from Cloudflare on both Main and original worker.
The deployed Java AudioDownloadRouter successfully fetched the same URL;
64,210,427 bytes, matching historical download size. SHA256 now
49679240fb1cd137eeb351190ac158703b1c419b6436c00e0f66c0e54fa9136c.
Historical file hash was not recorded. No bypass headers or manual queue writes.

Actual replay started2026-10-08T06:04:04.578980UTC, container
4b0e9ad1b050829b29f1b54287cc12d516005c18ac4c1be2eb8d1865b926c9b5,
name refine-offline-replay-20261008. Runner PID1482259 records cgroup CPU
stats every10s, GC log and terminal state;25-minute timeout stops the probe.
Private root Main:/home/bekzat/refine-replay-20261008. Credentials and full logs
must remain private. Container exits when probe completes; restart=no.

## Completed result

Exit0, no OOM or selected operational errors. Runner confirmed completion
2026-10-08T06:12:20UTC; temporary container stopped,restart=no.
All pre-existing running container IDs unchanged. No permanent worker added on Main.

- Processing469675ms (7m49.675s), versus717428ms (11m57.428s):34.53% reduction.
- Total probe elapsed486.221s (8m6.221s), includes response generation/startup.
  Original production processing_ms also excluded final buildResponseJson cost.
- Raw matches99; one final recognized track.
- Complete `matches` JSON array equals the original Redis response exactly,
  including identifier1441159294,ISRC TRBSF2401613,query3295.4–3304.3s,
  reference124.5–133.4s,score314. This is one-recording agreement, not a full
  catalogue quality regression test.
-169 logged lookup calls, mean2.284s,max7.44s. Historical count177 and mean5.471s;
  differing count underscores this is not a bit-for-bit controlled runtime replay.
- Replica query log for Main client during replay:1431 fingerprint SELECTs,
  avg174.1ms,p95386.5ms,max817ms; logical results12.89GB,wire6.63GB.
  Aggregate send elapsed140.23s,CPU176.63s. Historical449.6ms query mean covered
  ALL SIX far-node consumers, so don't use it as a task-specific SQL speedup ratio.
- Sampled peak JVM heap3,107,827,712 bytes (~2.89GiB). GC log1934 pauses,
  total95.345s,max429.058ms. CPU near final sample1114.4CPU-seconds across485.5s,
  ~2.30cores;1074/4855 quota periods throttled,59.17s cumulative throttled time.
  CPU/GC cost remains material even on the shorter network path; do not attribute
  all improvement exclusively to latency or all remaining time to ClickHouse.

Conclusion: same recording/settings/JAR completed below8minutes processing on
near-replica placement, with identical final detections. Supports placing compute
near database, while holding catalogue/runtime/load differences explicit.
No recognition thresholds, production topology or Redis entries changed by replay.
