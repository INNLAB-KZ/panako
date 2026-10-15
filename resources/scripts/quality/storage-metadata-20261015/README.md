# Storage duplicate metadata hotfix — 2026-10-15 Almaty

## Failure and repair

Live verification found one unresolved request, `FRX762448293`, at 2026-10-14 19:15 UTC. The stack ended in `OlafStrategy.metadata`, called by `LongAudioDuplicateCheck.find`, with a null metadata dereference.

The duplicate check re-resolved `candidate.refPath` into an identifier even though the query already supplied `candidate.refIdentifier`. Renaming metadata paths can change their hash while preserving the stored identifier. A deleted metadata row can also disappear between lookups. The exception alone does not distinguish these cases for this production request.

The fix looks up OLAF metadata using the query's resource identifier and returns null for a genuinely missing row. Missing metadata cannot establish a verified duplicate. The regression test changes a stored reference path while retaining its identity, verifies full-audio duplicate recognition still succeeds, verifies missing metadata does not throw, and still rejects a changed interior. Recognition thresholds are unchanged.

## Verification

- Java 17 source compilation and fat JAR build succeeded.
- Twelve tests passed against workspace classes, then twelve passed against the exact deployed overlay JAR: chunked store/duplicate checks (4), monitor window failures (6), hit conversion (2).
- Baseline JAR: `947e7ed08a6d15b90ec0af8cbfc58ce9dd99af25344bbd63abec2634b3d6a5c5`.
- Deployed JAR: `f56b0f3c31ce8af43396539ea27c672ba2bc656ab25104fe44f7b6d46c5dfb2d`.
- Only `OlafStrategy`, `LongAudioDuplicateCheck` and its anonymous callback class were overlaid; all other baseline archive entries verified byte-for-byte unchanged.
- Cooperative drain waited for the active task to finish. Rendered Compose comparison verified that only the storage image changed. Same Redis, database endpoint, memory/CPU limits and stream subscriptions retained.
- Container started at 2026-10-14 19:20:56 UTC. At 19:21:21 UTC: HTTP health 200; 12 responses after restart (1 new store, 11 already existing); one pending task; zero severe errors, OOM or automatic restarts. This is an initial acceptance sample, not a long-term reliability guarantee.
- Stage-1/refine containers and ClickHouse were not redeployed.

## Remaining state

The already-published error for `FRX762448293` still needs a normal orchestrator retry. This hotfix did not manually modify Redis requests/results, replay that request, or drain the existing backlog.

## Operations

`deploy.py prepare` builds the image from the exact currently running storage image plus the checked JAR. `apply` requires the previous incident's cooperative drain helper to report that the worker thread exited, then replaces only the image. It is a dated, guarded deployment script, not a general fleet rollout command.

Protected artifact, Compose backup, plan, logs and verification: `/home/bekzat/storage-metadata-20261015/` on Main and local `build/storage-metadata-20261015/`. The previous image remains available. Rollback requires finishing or recovering active work before restoring the saved Compose image; do not kill a running storage task or restore the original OOM-prone workers.
