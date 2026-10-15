# Redis storage queue status — 14 October 2026

Read-only investigation requested while expanding the separate project's refine pool. No storage service, queue, response or ClickHouse data was changed.

## Queue snapshot

Redis 10.0.0.2, `panako-store-requests`, group `panako-workers`, at **17:36:48 UTC / 22:36:48 Almaty**:

| Metric | Value |
| --- | ---: |
| Waiting messages read directly after the group cursor | 100,004 |
| Distinct complete requests, including all Redis fields/raw JSON | 28,925 |
| Excess exact copies | 71,079 |
| Distinct request IDs | 28,820 |
| Distinct nonempty input_isrc values | 28,834 |
| Distinct exact audio URLs | 28,820 |
| Pending requests | 0 |

Earlier direct count at 17:35:18 UTC was 100,013; the queue changes during investigation. Requests were read to a captured upper stream ID. Concurrent deletion/trimming was not excluded, so these are bounded operational snapshots rather than transactional snapshots. ISRC count is an indication of distinct queued track identifiers, not proof that all are absent from ClickHouse. Existing metadata/fingerprints were not reconciled per track, and signed URL changes can affect URL identity.

Latest `panako-store-results` entry at the 17:35 check was **7 October 09:50:53.798 UTC / 14:50:53 Almaty**. Response group had zero lag/pending. All six checked storage containers had zero Redis/Kafka `store completed` log events in the preceding 24 hours. The bounded Redis evidence shows no active ingestion of this queue; it does not measure every possible HTTP or Kafka ingestion path.

## Worker evidence

Four storage containers run on Main 116.202.112.101 (`store-a` through `store-d`); two run on 167.233.61.228 (`project-panako-store-1/2`). All are Docker-running with restart count zero and `OOMKilled=false`. This Docker flag does not exclude an in-JVM heap failure.

Bounded retained logs show `OutOfMemoryError: Java heap space` on five of six:

- Main store-a: 2 October 00:11 UTC, `OlafStrategy.query` through `findContentDuplicate` / `handleStoreRequest`.
- Main store-c: 2 October 01:11 UTC, same duplicate-check path.
- Main store-b: 1 October 17:19 UTC, reclaim/Kafka thread errors; an older duplicate-check OOM is also retained from before the current start.
- 167.233.61.228 store-2: 7 October 08:22 UTC, `OlafStorageClickHouse.processQueryQueue` / duplicate-check path.
- 167.233.61.228 store-1: 7 October 09:52 UTC, duplicate-check path, shortly after the last result.

No OOM was found in the last 20,000 retained lines of Main store-d; its exact reason for not consuming remains unverified. The 1 October store-b events do not all arise in duplicate checking.

A current thread dump of store-1 shows janitor and reclaim threads sleeping, with all three pool threads waiting in `ThreadPoolExecutor.getTask`. No active `PanakoRedisStreamWorker.run` consumer frame appears. Its JVM remains alive. This supports a terminated consumer task following the recorded OOM; retained logs and the thread snapshot do not fully establish every container's failure path.

## Next work

Investigate the deployed storage artifact and actual JVM heap configuration, fix the memory-intensive duplicate-check path, and ensure a fatal consumer failure is visible to health/restart monitoring. Validate with an isolated store test and one production canary before restoring the whole storage pool. Do not blindly restart all stores or delete the queue: pending audio may still need indexing, and exact duplicates need a separately reviewed cleanup plan.

Private evidence: `build/storage-status-20261014/` locally; `/home/bekzat/storage-status-20261014/` on 167.233.61.228. Thread/log dumps remain private. This turn performed diagnosis only; storage recovery is not claimed complete.
