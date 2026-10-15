# Storage recovery, 2026-10-14

## Runtime

- Main `116.202.112.101`, container `panako-storage-recovery-20261014-store-1`.
- Redis `10.0.0.2`, `panako-store-requests`, existing group `panako-workers`.
- One serial ingestion consumer; maintenance `panako-store-patch` temporarily excluded.
- ClickHouse Main via `127.0.0.1:8123` in host networking. Docker bridge to Main private IP failed connection initiation; original host route verified with authenticated SQL and real inserts.
- Heap 2 GiB; container 3 GiB; CPU limit 2; upload limit 512 MiB; `-XX:+ExitOnOutOfMemoryError`; restart `unless-stopped`.
- Six stalled old storage containers stopped with restart disabled. Their Compose service replica counts set to zero; rendered configuration checked for unrelated changes.
- Other Stage-1/refine containers were not redeployed.

## Build and evidence

`build-release.py` compiles an explicit source allowlist against the verified `7a24e3f...` JAR and copies only those classes into the release. Every other baseline ZIP entry is verified byte-for-byte. It does not package the dirty working tree wholesale.

Final artifact: `947e7ed08a6d15b90ec0af8cbfc58ce9dd99af25344bbd63abec2634b3d6a5c5`.

17 tests passed against the actual release JAR: 4 store/window tests, 7 Redis dispatcher tests, 3 download-length tests, 3 request-string parsing tests. A separate stage1 drain fixture verified that the active task and heartbeats finish without interruption and no next task starts. The final test invocation excluded recompiling workspace classes because `MonitorHandler.java` disappeared from the shared working tree during this session; the isolated release retains the verified baseline implementation.

Private evidence: `build/storage-recovery-20261014/` locally and `/home/bekzat/storage-recovery-20261014/` on Main. Credentials and payloads stay there, outside Git. Source backups, release hashes, Compose configuration, raw logs, original retry requests, retry receipts and acceptance reports are retained.

## Changes

- Full-audio duplicate checking in overlapping 30-second windows; consistent reference and offset required, existing quality threshold retained. A changed interior must not be skipped merely because intro/middle/outro match.
- Bounded extraction validates decoder completion and uses locale-independent offsets.
- Long audio indexing in five-minute chunks; incomplete metadata is not accepted as a completed index.
- Database insert failures propagate instead of reporting successful storage.
- Existing serial Redis admission and task heartbeat included; failure does not falsely ACK the request.
- `REDIS_STORE_PATCH_ENABLED=false` isolates ingestion from long-running maintenance deletes. Default remains true for existing configurations.
- Two requests affected by the initial network configuration were retried through normal consumer processing; successful responses were accepted by the orchestrator. Only the newly-created, completed retry entries were removed, preventing later duplicate fresh delivery. Original requests/results were not deleted.
- One legitimate 109.7 MiB source exceeded the old 100 MiB cap; upload limit raised to 512 MiB and that request replayed.

## Observe

Run `python3 /home/bekzat/storage-recovery-20261014/observe.py` on Main. Inspect actual response progress, pending ownership, new metadata, insert logs, memory, restarts and unresolved failures. HTTP health alone is insufficient evidence of consumer progress.

Redis's native lag becomes unavailable when entries ahead of the delivery cursor have been deleted. The observer also calculates a bounded atomic read-only backlog count when the retained consumed prefix fits its limit. No production cursor was reset. See [Redis command documentation](https://redis.io/docs/latest/commands/xgroup-setid/) for the distinction between delivery position and lag accounting.

## Stop / rollback

Use the validated `drain-control.py CONTAINER_ID drain`, wait for that consumer's active ingestion to finish and ACK, then stop the recovery container. Preserve pending work. Resume with the same tested artifact and protected Compose configuration after correcting the failure. Do not automatically restore the old OOM-prone workers.

Legacy Compose backups are `legacy-compose-before-private.yml` on Main and `167.233.61.228`. The retirement script checks rendered settings and restores the original file if validation fails.

## Remaining separate work

- Maintenance queue: 55 unread deletion/rename requests plus 2 pending at the latest observation. It remains paused; slow ClickHouse delete mutations were observed without mutation failure messages.
- Producer retention/reconciliation: see `STORAGE_QUEUE_RECONCILIATION_PROMPT.md`. The retained stream boundary had passed the old consumer position before recovery. Restoring the consumer cannot recover already-evicted requests by itself.
- One working consumer is a deliberate initial capacity choice. Both ClickHouse containers have 60 GiB memory limits each on a 125.5 GiB host. Scale only after memory budgets and deletion load are reviewed.
