# SQL failure propagation canary — September 29, 2026

OlafStorageClickHouse.processQueryQueue previously logged SQLException and
returned accumulated hits, allowing incomplete query data to reach recognition.
It now clears partial hits and throws IllegalStateException with the SQL cause;
queryQueue is still cleared in finally. MonitorHandler propagates failed windows
and the Redis handler publishes a task error rather than full success.
No query thresholds, hashes, batching, sorting, or parallelism changed.

Three new fault-injection tests failed against the old implementation, then
passed against the fix: failed connection, second-batch failure after returned
hits, and a result-stream failure after its first row. They verify no partial
hits survive and the next task does not inherit failed query hashes. A window
aggregation test confirms the SQL cause propagates through the failed future.
Final targeted suite: 15 tests, zero failures (3 storage, 6 window, 6 dispatcher).

Existing unrelated local changes to store failure handling were preserved in
source but deliberately excluded from the production canary artifact. Artifact
was constructed from the exact deployed JAR, replacing only
be/panako/strategy/olaf/storage/OlafStorageClickHouse.class. Normalized javap
comparison confirms only processQueryQueue bytecode changed; other methods and
all other JAR entries retain production behavior. No sorting optimization.

Baseline JAR SHA-256:
5d50209bf724eec120623c22e282b68de62c8b46fabd4712eed01615808db022
Canary JAR SHA-256:
e9f2df251e8898eae8d6343f58089eb5a555e9ed281c7fa6ee31a7235b264909
Image: local/ozen-panako:sql-failure-20260929-e9f2df25.

Deployed only project-panako-api-memory-1 on 167.233.61.250.
Healthy at 06:18:50 UTC / 11:18:50 Almaty. New ID:
ab691f5e4abcd1f8197739e4384884858e8e122a01a325bb1f26c096f2968b88.
The Compose service environment and runtime settings were verified unchanged:
MAIN 10.0.1.2, two windows, one Redis task slot, 3584 MiB heap, 4.5 GiB limit.
No pending task at handoff; unrelated container IDs unchanged.

Private deployment, before-inspect, observer, and guarded rollback:
/home/bekzat/sql-failure-20260929/ on 167.233.61.250.
Persistent override:
/home/bekzat/project/docker-compose.sql-failure-20260929.yml.
The 30-minute watcher began 11:18:51; expected gate approximately 11:49 Almaty.
At 11:24:30, worker healthy, one pending task with fresh heartbeat, no errors,
restarts or OOM. Twenty-five other Stage-1 workers retain the previous image;
canary rollout is not equivalent to a fleet-wide fix. No automatic expansion.

Keeper preparation is in ../keeper-ha-20260929/. An isolated same-version
rehearsal found that changing the original localhost Raft endpoint with the
same ID is ignored. A second rehearsal passed using a temporary second process
on MAIN (new ID 4), removing old ID 1, then joining IDs 2 and 3 sequentially.
Marker data survived, and reads plus writes worked after stopping new MAIN
Keeper. Production Keeper is unchanged; client reload/session migration and
consistent backups remain preconditions for live execution.

## Completed 30-minute gate

PASS at 06:48:57 UTC / 11:48:57 Almaty. Three tasks completed; two recordings
in the 3300–3900-second hourly cohort averaged 302.870 seconds (5:03), maximum
406.437 seconds (6:46). Observed task-service upper bounds averaged 330.459
seconds (5:30), maximum 435.170 seconds (7:15). No gate reasons or warnings;
TCP retransmission ratio zero. No observed errors, OOM, or container restarts.
The third recording was 3968.5 seconds, outside the hourly cohort, and took
494.779 seconds (8:15). Small sample: sufficient for this canary gate, not a
fleet performance guarantee. Only one worker has the new artifact.

## Later event: expansion held

Pre-rollout inspection found one Java heap OOM at 07:47:55 UTC, after the initial
observation window. Same audio input also preceded five baseline-worker OOMs
across the fleet. See 2026-09-29-stage1-oom.md. Historical 30-minute PASS must
not be used alone to authorize expansion. No further workers were updated.
