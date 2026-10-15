# Legacy services moved to Main; ch08 retired

## Applied 2026-10-07 (UTC)

- 167.233.61.228: `project-panako-api-http-1`, `project-panako-store-1`, `project-panako-store-2` now use Main 10.0.1.2:8123 instead of 10.0.0.23.
- Preserved exact deployed image sha256:83b837e9bd0eb0546e413a220294834eb4ecbe4bfdd82d2b6a1cbc63892993b6, command, memory, ports, mounts and all other runtime environment values.
- Existing base Compose differed from runtime. Frozen actual environment for these two services in mode-600 Compose; `.env` unchanged. This avoids accidental monitor setting changes.
- Fixed base `panako-api` CH route too. Effective configuration with existing six Compose layers remains identical; four running Stage-1 container IDs unchanged.
- Store consumers had no owned pending messages before pause and after six-second race check. Recreated only these idle stores. No manual Redis ACK, claim, deletion or requeue.
- HTTP was already unresponsive: all ten HTTP pool threads waiting for request lines, 831 established connections. SIGQUIT dump showed no active application handler. Recreation restored health and authenticated stats. Request/header timeout root cause remains unresolved.

## Replica registration correction

Store DELETE operations returned code 341 `UNFINISHED`, naming inactive ch03. These operations continue asynchronously in ClickHouse.

Verified stopped containers and macro identities directly:

| Host | Replica | State |
| --- | --- | --- |
| 167.233.105.218 | ch03 | Already stopped, restart=no |
| 167.233.105.74 | ch04 | Already stopped, restart=no |
| 91.99.52.184 | ch08 | Stopped this step at 06:24:54 |

At 06:22:51 removed only ch03/ch04 registrations for `default.olaf_fingerprints` and `default.olaf_metadata`. At 06:25:15 removed ch08 registrations after verifying it inactive. Every operation guarded Main writable, session healthy and >=5 active replicas. Saved registration-child metadata and results privately on Main under `/home/bekzat/replica-registration-retire-20261007` (not a complete Keeper backup).

`SYSTEM DROP REPLICA ... FROM TABLE ...` removes Keeper registration; it does not remove table data on disk. [ClickHouse documentation](https://clickhouse.com/docs/reference/statements/system).

## ch08 retirement

- Runtime restart policy `no`; `/srv/clickhouse/docker-compose.yml` restart=no and deploy.replicas=0.
- Data, config, users and logs remain under `/srv/clickhouse`.
- Other running container IDs unchanged, including three real refine consumers on this host.
- Fresh endpoint inventory found no running application CH routes to .23; candidate had no active queries.
- Retirement gate allowed only error-free MUTATE_PART work, <=1 attempt, age<600 seconds, absolute_delay=0. No fetch backlog accepted. Continuous store deletes made an entirely empty mutation queue unnecessary for decommission.

## Verification

- All three migrated services running, health=ok, RestartCount=0. Authenticated stats succeeded against Main.
- Natural Redis store task ITA680700092.m4a completed in 1608 ms at 06:23:19; successful Main INSERT queries confirmed.
- Two code-341 errors appeared after registration cleanup, but query_log proves they began before cleanup: starts 06:20:22 and 06:21:13, finishes 06:23:02 and 06:25:40. Do not interpret them as newly started failures.
- No DELETE started after 06:22:52 was present in query_log at final check. Successful new synchronous DELETE still needs observation; do not claim mutation path fully validated.
- Both tables on all remaining five instances: total_replicas=active_replicas=5, readonly=false, session_expired=false, absolute_delay=0. One or two unfinished fingerprint mutations, latest_fail_reason empty.
- Main short five-second sample: CPU busy 21.3%, iowait 0%, MemAvailable 116.5 GiB, physical ens23 TX 2398 Mbps/RX 13.3 Mbps. Error/drop deltas zero. VLAN counters overlap physical counters; do not add them. This is not a peak-capacity benchmark.

## Remaining topology / work

- 26 Stage-1 and 28 real refine workers; counts unchanged. Legacy HTTP has mode=refine but REDIS_ENABLED=false and is not an additional queue worker.
- Main ch01 and clickhouse2 ch02; three external replicas: .12/ch05 (167.233.101.144), .10/ch06 (167.233.107.246), .22/ch07 (46.224.159.198).
- To reach two external replicas, first migrate the six refine consumers on 62.238.0.200 currently using .22. Earlier far-host Main pilot exceeded latency target; use measured placement, not blind endpoint replacement.
- Store backlog pre-migration: lag 99,811 normal /138 patch. Current old store image has historical OOM and reclaim concurrency concerns; this step only changed routing. Backlog completion and quality are not established by one successful insert.
- Fix HTTP header/request timeout separately.
- Hour-long recording target remains 8–10 minutes; the 1.6-second store task is not a recognition benchmark.

## Backups / rollback

- 61.228 `/home/bekzat/legacy-main-20261007`: original inspect, Compose, deployed proposals, deployment and completion evidence. Contains secrets; do not commit or print.
- 91.99 `/home/bekzat/ch-retire-20261007`: original Compose/inspect, proposed Compose, guarded stop script, status.
- Local private artifacts: `build/legacy-main-20261007/private`.
- After registration removal, simply starting a retired container is not a complete rollback. Its tables may be read-only. Re-register deliberately using the documented `SYSTEM RESTART REPLICA` / `SYSTEM RESTORE REPLICA` process as appropriate, validate synchronization, then restore clients. Retained local parts can be reused; stale parts may require fetching. Never manually recreate Keeper trees from partial snapshots.
- Scripts under `resources/scripts/quality/legacy-main-20261007` are operation records with one-time guards, not an unattended reusable rollout command. Private inputs and Redis helper are intentionally excluded.
