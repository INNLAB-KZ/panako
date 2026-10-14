# Refine backlog audit and prepared cleanup — 2026-10-13 Almaty

## Cleanup completed after explicit authorization

The user explicitly authorized deletion of the verified duplicates, maximum 213400 entries. The user also requested a standalone Markdown prompt for the separate orchestrator project; no edits to that project are part of this work. Prompt: `ORCHESTRATOR_REFINE_DEDUP_PROMPT.md` at this repository's root.

Live precheck at **19:13:37 UTC October 12 / 00:13:37 Almaty October 13** verified the exact tested script hashes, the single expected group and presence of all 28 pending payloads. Request lag before deletion: **238522**. The script used the approved immutable snapshot and manifest; no newly arrived requests were added to the deletion scope.

- Pilot: **1789** duplicates deleted across 100 retained-request groups at 19:13:53 UTC. All 100 keepers remained present, all 1789 deletions were verified, and all 28 current pending payloads were present.
- Main pass: another **209836** duplicates deleted, completed **19:16:31 UTC / 00:16:31 Almaty**. A total of 112 keeper groups were skipped because their keeper had already been delivered.
- Total deletion: **211625**, within the authorized maximum. **1775** authorized candidate duplicates were not deleted because the runtime guards no longer permitted deletion.

Full verification at **19:17:41 UTC / 00:17:41 Almaty** scanned the surviving stream and checked every snapshot entry not actually deleted. All **26978 retained snapshot entries** were present with identical payload fields. Every journaled deleted ID was absent. Deleted IDs were a subset of the approved manifest, with no duplicate deletion receipts and no retained keeper in the deletion set. All original pending payloads and all 28 current pending payloads remained present.

Direct bounded count: **26874 unread entries**, **25200 distinct exact requests**, including **1674 remaining exact duplicates**. Sixteen entries were added outside the original snapshot and were not part of the cleanup. Counts change as normal consumers and producers continue. Redis `lag` and `entries-read` are temporarily null after XDEL; the delivery cursor was not reset or edited to fabricate a counter.

Post-cleanup check at **19:18:18 UTC** found **14 successful refine responses** since the final deletion pass and no error responses in that short interval. The orchestrator response group had lag/pending zero. Stage-1 request lag remained zero, with five pending tasks. This is an immediate operational check, not a long-term SLA test.

The fleet remains **27 Stage-1 + 29 refine**. The proposed conversion of two Stage-1 containers was not performed after duplicate submission was established. No active task was interrupted, no manual ACK/claim/requeue or group-cursor modification was used, and no recognition settings changed.

Evidence: `build/refine-capacity-20261012/{before-cleanup,pilot-verification,final-verification,cleanup-receipt}.json`, verified independent snapshot/manifest backups, and `deletion-journal.jsonl`. The cleanup is complete within the approved scope; preventing new duplicate submission belongs to the separate orchestrator code task described in the prompt.

## Result and change in rollout plan

The user approved proceeding with backlog inspection and a two-container Stage-1-to-refine pilot. Read-only inspection found extensive duplicate submission, so the capacity conversion was deferred before any deployment change. The fleet remains 27 Stage-1 + 29 refine, including the isolated pair. Before explicit cleanup authorization, this task had only read the request stream; the authorized deletion is recorded above.

At 18:43 UTC October 12 / 23:43 Almaty: main Redis Stage-1 lag 0; 71 successful responses in the preceding hour, with 67 near-hour recordings averaging 282388 ms and p95 373807 ms. Refine lag 238732; 53 requests/hour and 447 successful responses/hour, including the one explicitly authorized manual recovery publication. The earlier approximately 25-day estimate assumed every queued entry required a separate execution; the duplicate audit invalidates that assumption.

## Verified snapshot

Snapshot completed **19:01:53 UTC October 12 / 00:01:53 Almaty October 13**:

| Measure | Count |
|---|---:|
| Undelivered entries in bounded snapshot | 238603 |
| Byte-identical duplicate entries, including all Redis fields | 213400 |
| Retained distinct requests | 25203 |
| Distinct recording IDs | 20566 |
| New entries during the preceding hour | 51 |
| Those new entries duplicating an existing backlog request | 43 |

The stream has one consumer group, `panako-worker-refine`, with 28 pending tasks at snapshot time. Only entries strictly after its captured last-delivered ID were included. Multiple segment sets for the same recording are retained as separate requests. Request fields are `key` and `value`, and every key equals its payload recording ID. The plan contains only exact field matches, including the complete raw JSON value; canonical JSON comparison yielded the same duplicate count.

This establishes continuing duplicate submission. It does not establish which upstream retry path or scheduler creates it. The local ozen-panako code consumes refine requests and emits responses; the request producer was not found in the inspected sibling source repositories. A recording's age alone is not grounds to discard it.

## Backup and immutable plan

Protected remote directory: `/home/bekzat/refine-capacity-20261012/backlog` on 167.233.105.218. Independent local backup: `build/refine-capacity-20261012/`. Both copies were verified against the same SHA-256 hashes. Direct transfer initially timed out; compressed transfer succeeded and original decompressed bytes were verified.

- Snapshot size: 118519413 bytes.
- Snapshot SHA-256: `f9bda9a9ad14dda8c164feada4b26ce9a24e1da723aad6a8fe5ccd5175e4f083`.
- Duplicate manifest SHA-256: `6bd03b94f74bc2a168cc89d3f7d7bc675cb4acc7100c4ed8e4e39f36981fe601`.
- Independent validation checks every proposed deletion against its exact retained entry; no retained entry appears in the deletion set.

## Cleanup preparation before authorization

`clean-backlog.py` validates the snapshot/manifest hashes and every duplicate relationship before use. `delete-exact-duplicates.lua` performs one bounded atomic operation per retained request: at most 100 duplicate IDs. It rechecks that exactly the expected consumer group exists, the retained request remains unread and present, all candidate payload fields match byte for byte, and no candidate has a pending entry. A missing or already delivered keeper causes that entire group to be skipped. Payload mismatch aborts before any deletion. Only XDEL of the verified duplicate IDs is permitted; no XACK, XCLAIM, XGROUP SETID or response publication is part of this cleanup.

Eight tests passed on disposable local Redis 7.2.7, accessed only through a private Unix socket: read-only plan, preserving one exact request and changed segments, repeat-run idempotency, consumer advancing before cleanup, missing keeper, additional consumer group, payload mismatch without partial deletion, and preserving an artificially pending duplicate. Test server terminated afterward. Production read-only Lua preflight evaluated 15 representative retained-request groups, identifying 193 eligible duplicates and deleting **zero**.

The prepared execution plan was: execute a small pilot, verify retained requests/pending integrity, then process the remaining fixed manifest and directly count remaining undelivered entries. That plan was subsequently authorized and executed as recorded above. Workers continue normal processing. The maximum proposed removal is **213400** entries; actual removal may be lower because consumers advance or entries change eligibility. New entries outside the frozen snapshot are not included.

Deleting undelivered stream entries can temporarily make Redis consumer-group `lag` unavailable. Use a direct bounded XRANGE count until Redis restores its logical lag counter; do not reset the group's delivery cursor. [Redis XINFO GROUPS documentation](https://redis.io/docs/latest/commands/xinfo-groups/).

Backups preserve original IDs and fields for audit. A logical restore would re-enqueue selected saved payloads with **new stream IDs**; old IDs cannot be inserted back into their original position in the live stream. Such restoration is a separate explicit action, not automatic replay of the entire backup.

## Follow-up in the separate project

Explicit bulk cleanup authorization was received and completed. The user requested a portable `.md` prompt instead of work in the separate orchestrator repository. `ORCHESTRATOR_REFINE_DEDUP_PROMPT.md` contains the evidence, requirements, concurrency/crash tests and deployment boundaries. Cleanup alone does not prevent recurrence.
