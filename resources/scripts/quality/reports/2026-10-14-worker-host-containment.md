# Worker host containment — 13–14 October 2026

## Incident and action

On `167.233.53.95`, two JVMs crashed with SIGSEGV at 14:12:15 and 14:12:45 UTC on 13 October. An independent ffmpeg process faulted in the dynamic loader at 14:12:25. Heap/container headroom was present. These correlated native crashes justify isolating the VPS, but do not prove a hardware, hypervisor or JVM defect.

All six Stage-1 containers on that host finished their owned tasks before retirement. Their pending counts were verified zero; restart was disabled and the final Compose override set to replicas=0. Original containers, data and private crash evidence remain available. Isolation completed at 18:16:26 UTC. The host must not return to the worker pool until investigated.

No speculative JDK update or rebuild of the dirty local workspace was deployed. All active monitor workers retain JAR SHA-256 `7a24e3f7e2d1be928211c1b335a09c16a39d58d5e05f61b7cb76cdafb839298b`.

## Capacity and placement

RAM reservation gates allowed two additional containers on existing hosts. To restore Stage-1 capacity while keeping the suspect VPS isolated, four refine slots were temporarily reassigned after cooperative draining.

| Host | Stage-1 | Refine | Notes |
| --- | ---: | ---: | --- |
| 46.224.159.198 | 4 | 0 | One evacuation replacement plus three former refine slots |
| 91.99.52.184 | 1 | 3 | Only refine-d was exchanged; replica-routed refine retained |
| 167.233.105.74 | 4 | 0 | Existing workers |
| 167.233.105.218 | 0 | 4 | Existing workers |
| 167.233.61.228 | 5 | 1 | Includes separate Redis 10.0.0.4 pair |
| 167.233.61.250 | 6 | 0 | Existing workers |
| 167.233.52.194 | 6 | 0 | Existing workers |
| 167.233.62.118 | 0 | 6 | Existing replica routing |
| 157.180.26.0 | 1 | 5 | One evacuation replacement |
| 62.238.0.200 | 0 | 6 | Existing workers |
| 167.233.53.95 | 0 | 0 | Isolated |
| **Total** | **27** | **25** | **52 containers** |

Main Redis 10.0.0.2: **26 Stage-1 + 24 refine**. Separate Redis 10.0.0.4: **1 + 1**, unchanged. Total fleet capacity is four slots below the original 56; the temporary cost is reduced refine throughput. Both external ClickHouse replicas remain in service.

The four-slot exchange completed at 19:01:13 UTC on 46.224.159.198 and 19:03:20 UTC on 91.99.52.184. Original refine containers were retained, their Compose replica counts persisted at zero, and unrelated container IDs remained unchanged.

All 52 active monitors were audited: two windows, 3584 MiB heap, 4608 MiB container limit, identical deployed JAR, no active-container restarts or OOM flags. Routes: 44 direct to private Main 10.0.1.2:8123, one through the existing private read router, six to replica 10.0.0.10, one to replica 10.0.0.12. The two separate-project monitors are included in these totals.

## Recovery and recognition verification

The failed recordings were processed offline without consuming or publishing queue messages. A repeat of identical audio with explicit JDBC `socket_rcvbuf=0&socket_sndbuf=0` produced identical normalized recognition results. The initial diagnostic took 18:43; the repeat took 9:13. CPU limits and JVM CPU discovery also differed across diagnostic runs, so this is not a single-variable transport benchmark. Production replacements use the original image and recognition settings, with the two explicit transport options on the new destinations except the first evacuation replacement on 46.224.159.198.

After explicit approval for this exact pair, both results were published atomically to `panako.monitor.response` on Redis 10.0.0.2 at 18:54:12 UTC. Their exact payload hashes were verified; the orchestrator group had passed both IDs and neither remained pending at 18:58:33 UTC.

| Recording | Processing | Tracks | Response stream ID |
| --- | --- | ---: | --- |
| 01a13592-15d3-7532-b253-03eef94d65ad | 9:13 | 20 | 1791917652209-0 |
| 01a13594-2460-78bb-a49c-216ca9aa373e | 8:09 | 6 | 1791917652209-1 |

Consumer-group acknowledgment confirms receipt/ACK in Redis; application database persistence was not inspected in the other project.

## Live verification

Final checkpoint: **14 October 00:09:38 Almaty / 13 October 19:09:38 UTC**. Each of the six replacement containers completed at least one real recording of 3492–3726 seconds, with successful Redis responses and matching local completion times. The four priority replacements completed their first recordings in **3:35, 4:47, 5:23 and 4:16**. No restart, OOM, failed window, fatal JVM error or ownership/heartbeat warning was observed on them.

For responses completed after 19:03:21 UTC, the main queue had **32 successful near-hour processing runs** (audio duration filter 3300–3900 seconds): mean **4:21**, p95 **5:26**, maximum **5:59**, none above ten minutes. These are completed processing runs, not necessarily distinct recordings; repeated producer requests can produce multiple runs. Some runs began before the final placement completed. This short checkpoint verifies processing performance, not a long-term capacity guarantee.

Stage-1 waiting backlog fell from **26 to 9**, with **26 tasks in progress** at the final checkpoint and no pending entry idle over 20 minutes. Queue waiting is separate from processing time. The short final window contained no unambiguous request/response pair both created inside that window, so it does not establish final end-to-end latency. The earlier wider window included request-to-response waits above ten minutes while processing stayed below ten minutes.

Main at 19:08:20 UTC, with a ten-second host sample:

| Metric | Value |
| --- | ---: |
| CPU busy | 48.54% |
| I/O wait | 0% |
| Available RAM | 115.91 GiB |
| Network TX | 7.169 Gbit/s |
| Network RX | 94.06 Mbit/s |
| Interface errors/drops in sample | 0 |
| Fingerprint SELECT p95 by client, prior 15 minutes | 271–382 ms |
| Query errors, prior 15 minutes | 0 |
| Replication queue / delay | 0 / 0; 4 of 4 replicas active |

ClickHouse2 on port 8125 had no fingerprint SELECT traffic in that window. Sampling does not exclude unsampled traffic peaks or TCP retransmissions.

Target host samples at 19:02 UTC: CPU 79.05% / 56.05% / 62.85%, available RAM 11.57 / 11.07 / 15.43 GiB on 46.224.159.198 / 91.99.52.184 / 157.180.26.0 respectively. Private-network RX was 768 / 540 / 813 Mbit/s, with zero interface errors/drops and zero I/O wait in these samples. The 91.99.52.184 sample preceded its final role exchange.

Fleet audit: `build/host-isolation-20261013/priority-final/`. Final timing snapshots: `*-post-priority-final.json`; bounded observer snapshots retain earlier per-container completions.

Two ownership-loss warnings on existing refine workers at 15:37 and 15:42 UTC predate this relocation. Both request payloads remain present, neither is pending, and successful responses were found. No new ownership warning was observed on the replacements.

## Remaining external work

- Ask Hetzner to investigate the isolated VPS/physical host. [Support draft](../host-isolation-20261013/HETZNER_SUPPORT_DRAFT.md) is prepared and **not sent**. The physical root cause remains unconfirmed.
- Fix duplicate enqueue in the other project's orchestrator using the requested [standalone prompt](../../../../ORCHESTRATOR_REFINE_DEDUP_PROMPT.md). That project's code was not changed. No further duplicate deletion was performed in this incident task.
- Restore four lost fleet slots after host remediation or new capacity; then reverse the temporary refine-to-Stage-1 exchange if appropriate.

The refine snapshot at 19:08:22 UTC contained **38,639 waiting messages**, **15,612 distinct complete requests**, and **23,027 excess exact copies**. Of 12,754 still-waiting messages added in the preceding hour, 12,666 repeat an earlier waiting message. This is ongoing producer duplication, not evidence that the four-slot exchange generated duplicates. The prompt includes this latest evidence. Refine's Redis `lag=null` after earlier deletion is not interpreted as an empty queue; the waiting range was read directly. Source of repeated enqueue remains to be established in the other project.

## Evidence and rollback

[Containment procedure and rollback](../host-isolation-20261013/README.md). Private remote artifacts: `/home/bekzat/host-isolation-20261013`; local evidence: `build/host-isolation-20261013`. These include configuration backups, source image/hash receipts, deployment receipts, bounded observations and publication receipts. Do not commit credentials, full JVM reports, audio or recognition payloads.

The temporary authenticated image-transfer listener was stopped and its credentials removed. Offline diagnostic containers are stopped. The bounded local observer was stopped after every replacement passed a real-task check; no continuous monitoring is implied. Production services retain their normal restart policies; retired services have restart disabled and persisted replicas=0. Python syntax checks passed for the publication, priority exchange and observation scripts; production verification used configuration/hash checks and actual task completion.
