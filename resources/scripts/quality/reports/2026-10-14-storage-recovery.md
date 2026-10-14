# Storage ingestion recovery — 2026-10-14

## Result

Ingestion into ClickHouse Main restored. At 2026-10-14T18:48:07.213147+00:00 (18:48 UTC; 23:48 Almaty), the recovery produced 243 responses: 54 new stores, 185 already-existing resources, one content duplicate, and three initial failures. All three failed requests were subsequently recovered; no unresolved error keys remained in the observed response range. Successful recovered responses were acknowledged by the orchestrator.

One storage consumer remains active on Main. The queue is not empty: 99,951 unread messages plus 1 in progress at this snapshot. Counts include duplicate requests. This incident repair restores processing; it does not claim that the backlog is drained or that all historical evicted work has been recovered.

## Root cause and repair

Old consumers exhausted Java heap in whole-file content-duplicate queries. Consumer threads stopped while JVMs and Docker containers remained running. The new release bounds duplicate checks to overlapping 30-second windows covering the entire recording, and bounds long-file indexing to five-minute chunks. It requires consistent reference alignment, preserves recognition thresholds, propagates failed database inserts, and uses serial Redis admission with a heartbeat. JVM exits on OOM so Docker can restart it; failed tasks remain pending.

The six stalled old storage containers were retired: four on Main, two on 167.233.61.228. Their restart policies and original Compose replica counts prevent accidental reactivation. Monitor/refine deployments were unchanged.

## Production verification

- Artifact `947e7ed08a6d15b90ec0af8cbfc58ce9dd99af25344bbd63abec2634b3d6a5c5`; explicit overlay on a verified baseline, not a full dirty-workspace build.
- 17 tests passed against the release artifact, including changed-interior rejection, chunk-boundary recognition, pending recovery, heartbeat, and incomplete downloads.
- RAM limit 3 GiB, heap 2 GiB, CPU limit 2, upload cap 512 MiB, `ExitOnOutOfMemoryError`, `unless-stopped`.
- Current process: zero OOM, zero automatic restarts, zero severe log errors; HTTP health passed and real queue progress/database writes verified.
- A 114,998,189-byte file (109.7 MiB), duration 3570.5 seconds, stored 120,508 fingerprints. The indexing portion took 36.014 seconds. This measures storage indexing, not Stage-1 recognition latency.
- New metadata and successful insert batches verified on Main. One observed resource had duration 1,047.84 seconds and was indexed across four chunks.
- Main host sample at 18:34 UTC: CPU 66.18%, network TX 6,741 Mbit/s, RX 91.8 Mbit/s, no interface errors/drops. All four ClickHouse replicas active, replication delay zero. This is one sample, not a sustained capacity guarantee.

## Incidents during rollout

1. Docker bridge could not connect to Main private endpoint. The canary was stopped; original host-network localhost endpoint restored. Two affected requests were redelivered through the normal consumer and accepted by the orchestrator.
2. Old 100 MiB upload limit rejected one valid 109.7 MiB source. After a cooperative drain, limit raised to 512 MiB; request replayed and its successful insertion verified.
3. Only three temporary retry entries created by this recovery were removed after successful results and orchestrator ACK. Original request entries and existing result entries were not deleted. No bulk deduplication or group cursor reset occurred.
4. Native Redis `lag` is null after deletion of the completed retry entries ahead of the delivery cursor. The observer supplies a bounded, atomic read-only unread count. It must not treat null as zero. A proposed lag-accounting repair was tested locally, found ineffective, and not applied in production.

## Remaining separate work

- `panako-store-patch` maintenance consumption temporarily disabled: 55 unread, two pending at the observation. Existing DELETE operations had 60-second JDBC timeouts; three background fingerprint mutations were still finishing without recorded failure. This queue needs its own repair and must not block ingestion.
- Request stream retained only approximately 100,000 entries. Its retained first ID had moved past the old stopped consumer cursor before recovery. Reconcile missing catalog work against the orchestrator's durable state; Redis alone cannot reconstruct evicted requests. Producer cause still needs confirmation.
- One consumer is the current recovery capacity. Further scaling requires a reviewed memory budget and protection against concurrent ingestion of the same resource, especially partial-store cleanup. Docker memory limits are caps, not actual reserved usage.

## Files

- Operational scripts and protected deployment configuration remain in Main `/home/bekzat/storage-recovery-20261014/` and the local incident workspace.
- Separate-project prompt: `STORAGE_QUEUE_RECONCILIATION_PROMPT.md`.
- Protected runtime/queue evidence: `build/storage-recovery-20261014/` and Main `/home/bekzat/storage-recovery-20261014/`.

## Source publication verification — 2026-10-15 Almaty

- Restored the missing `MonitorHandler.java`, including window failure propagation, bounded future waits, cancellation cleanup that waits for actual thread termination, and locale-independent numeric output.
- Included the previously uncommitted Redis dispatcher, OLAF memory/lookup fixes, incomplete-download checks, and their regression tests. Fixed invalid Redis worker Javadoc paragraph tags that blocked the build.
- Java 17 compilation, Javadoc, source JAR and fat JAR succeeded. The full test invocation ran 65 tests: 58 passed, 7 failed because this local environment lacks `liblmdb.dylib` and the upstream test audio files could not be downloaded. All HTTP/Redis, OLAF query failure/timeout, hit conversion, quantization impulse, chunked storage and monitor clustering tests passed; none of those tests were skipped.
- This source publication does not redeploy containers or claim that the storage backlog is empty. Production verification above refers to the explicitly identified recovery artifact.
