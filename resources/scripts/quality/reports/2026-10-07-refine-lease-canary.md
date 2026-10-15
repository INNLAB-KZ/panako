# Legacy refine lease upgrade canary

Host157.180.26.0; source project-panako-api-5,
ID e968a07740dc4229bd7595ab3a0e9f59fe165c7a6188134c6a547262a7ba713e.
Source identified in duplicate execution of recording01a0da08-8611-7266-a30a-e1e0226dd0f9.

New image local/ozen-panako:refine-lease-20261007-60a75e63 is built from exact
legacy source image83b837e9 with only /app/panako.jar replaced by the previously
validated JAR60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e.
No Java source compilation. Earlier build attempted FROM sha256:...; BuildKit
interpreted it as a repository and failed. Deployment preflight also failed before
stopping/changing any service because new image was absent. Resolved by tagging
the exact local base image, then successful local build with network=none.

Pilot retains full source recognition environment and CH10.0.0.12:8123.
Changes: JVM heap3GiB→3.5GiB, container4GiB→4.5GiB,
explicit Kafka disabled, CPU quota3, host networking with health port8094,
unique hostname/refine consumer. Two windows and one Redis worker thread retained.
Image contains previous dispatcher lease/ACK and JDBC fixes, not only a lease patch.

Source's shared Compose service has six replicas. Replacement plan reduces that
count to five without recreating the other five; starts one separately managed
refine-canary service. Config diff guarded to only replica count; source runtime
inspect and original Compose backed up privately. Source stopped with restart=no,
not deleted; canary own Compose starts on daemon restart. Total host remains six.

Drain waits up to180 seconds for zero owned pending entries, pauses and rechecks
for five seconds. Fallback is docker stop30s with pending IDs captured; normal
application reclaim handles interruption. This cannot promise exactly-once in the
mixed legacy/fixed fleet. No manual Redis ACK/claim/delete/requeue.

Private deployment root:/home/bekzat/refine-lease-canary-20261007.
Operational scripts recorded under resources/scripts/quality/refine-lease-canary-20261007.
Secrets, inspect and resolved environment kept out of repository artifacts.
Acceptance needs successful tasks, lease refresh evidence, no errors/restarts,
resource headroom and tracked source-task recovery if interrupted. A healthy
startup alone is not a full canary pass. Do not rollout remaining legacy workers
before reviewing this canary. R1 on91.99 remains active; its original source on
62.238 also remains active while migration gate is held.

## Applied

Source stopped2026-10-07 16:46:18UTC; new health passed16:46:21UTC.
New container56c364336a5eeba82363bd38dcd3ee44dd5eec72911e18002a163ffb9638ab47.
Other five running container IDs unchanged. Persistent original replica count5;
new separate project adds1, so host total6, fleet refine total29 during R1 overlap.

Idle drain timed out. One pending source message1790880540554-0 remained owned
by e968a07740dc-1-1, delivery count1, idle23.1s after shutdown. No ACK was issued.
Started30-minute resource observer PID3575201,40-minute task/lease watcher
PID3575202,40-minute source recovery watcher PID3575203. Their JSON outputs,
logs and observer-pids.json are on the destination under the private root.
Recovery and performance acceptance remain pending. Old source can be restored
from before.yml/source.json if pilot fails, after stopping pilot; do not run both
unintentionally. Retained original Docker image supports rollback.

Initial lease check:13 five-second samples recorded two idle resets for current
message1790880540594-0, 28880→3959ms and29514→4628ms, same owner and deliveries1.
This demonstrates heartbeat refresh on one actively processed task; it does not
establish long-run absence of mixed-version duplicates. First sampled host CPU63.5%,
available13.28GiB, private RX596.87Mbps, no error/drop increments. New task covers
1158s requested segments; it is not a full-hour workload performance sample.
Interrupted source recording01a09b68-61c6-7994-b490-0911dd620124 remains pending
at16:47:28UTC; recovery watcher active. No completed canary task claimed yet.

## 2026-10-08 05:41 UTC follow-up

Operational 30-minute observer passed, no alerts, three completions, minimum
available9.98GiB. Fresh bounded30000-line log check:20 completions, no selected
errors, ownership-loss, OOM or restarts; health good, available15.76GiB.
These20 completions are not a complete overnight count or all hourly workloads.

Interrupted source message1790880540554-0 recovered: response statusok523153ms,
no longer pending, recovery confirmed2026-10-07 17:04:56UTC.

Performance gate is NOT a full pass: 3600s recording01a09b28-d9e6-79cf-ac2b-998350d26eff
with3579s segments took717428ms (11m57s), above600s target.
Other near-hour full-segment samples453843ms and459584ms (7m34s /7m40s).
A4027.6s audio/3537s segment task took389488ms; do not classify as a strict
3300–3900s audio-duration sample.

Slow task lease evidence145 samples over16:51:08–17:03:28UTC: same owner,
max idle30003ms, delivery count1 throughout. Thus heartbeat worked across a
>10minute task; performance problem is separate from demonstrated lease renewal.
Fresh5.13s CPU sample used6.605CPU-seconds (~1.29cores),1/51 periods throttled,
20226us throttled. Does not establish cause of historical slow task or sustained
CPU headroom. No further rollout or configuration changes in this status check.
