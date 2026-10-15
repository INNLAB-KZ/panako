# Refine follow-up: ACK race and slow lookup path

## Ownership warning

- Request `1790881826066-0`, recording `01a0def8-10f7-7c52-b748-b1e976bf35e0`.
- Container `project-panako-api-5` on 157.180.26.0 completed at 13:11:14.035 UTC; the heartbeat warning followed at 13:11:14.144 UTC.
- One successful Redis response was found in 12:30–13:40 UTC: `1791465074018-0`, processing 410323 ms, audio duration 3594.1 s. The request is no longer pending.
- Bounded log searches on all six refine hosts found this recording only on the updated source host. This does not prove absence outside the searched interval or retained log tail.
- Code permits renewal to execute after a successful ACK, but before heartbeat shutdown. A regression test using the deployed JAR reproduces this false warning deterministically by delaying the ACK caller after Redis removes the PEL entry.
- Local fix serializes heartbeat renewal and final ACK using a per-task lock and completion flag. Real ownership checks remain in both Lua operations; processing and recognition settings are unchanged.
- Seven `RedisTaskDispatcherTest` tests pass with Java 17. The new test fails against the deployed JAR, reproducing the warning. Default Java/Gradle combination failed during test-task configuration; rerunning under installed Temurin 17 succeeded.
- This patch is local only; no replacement production JAR was deployed in this follow-up. The incident is consistent with the reproduced ACK race; historical Redis command ordering was not captured.

## Processing times

Read-only response scan joined recording IDs present in retained completion logs on 157.180.26.0 after wave deployment. Of 77 successful responses, 67 had audio duration 3500–3700 seconds: mean 390710 ms (6:31), ten above 600000 ms. Refine segment coverage varies, and response records do not identify the executing container; this is not a controlled full-hour benchmark.

Slowest recording `01a0de9e-a589-78f1-83b4-3469dde85da0` took 773456 ms (12:53), duration 3594.1 s. Its 177 client lookup calls averaged 6.377 s and peaked at 19.46 s; the union of overlapping lookup intervals covered about 722.866 s. These timers include batched SQL, network transfer, result parsing and allocations, not only database execution.

Replica 10.0.0.12 query log for 12:44:00–12:57:10 UTC, all six workers sharing client 10.0.0.11: 8076 fingerprint SELECTs, mean 610.6 ms, p95 1199 ms, maximum 3121 ms, zero query errors. Recorded network send bytes 35,992,782,314 imply about 364.5 Mbit/s averaged over 790 seconds; this is query-log accounting, not a physical interface measurement. It does not establish port saturation. CPU throttling and GC for that historical task were not captured.

The earlier same-audio replay near the replica improved 11:57 to 7:50 with identical returned matches. Placement remains a useful next experiment, with synchronized CPU/GC, network and SQL measurements. Do not change recognition thresholds to reduce processing time.
