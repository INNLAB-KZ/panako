# Bounded ClickHouse JDBC socket reads

## Cause

JDBC/client-v2 0.9.8 defaults `socket_timeout` to `0` (unbounded).
The affected direct-MAIN worker URL had no timeout/retry overrides.
The post-outage thread dump showed a window blocked in a native socket read,
through Apache HTTP chunk decoding and ClickHouse LZ4/ResultSet.next. Java
interruption alone did not release it; task heartbeat continued.

Verified version-pinned upstream sources:
- https://github.com/ClickHouse/clickhouse-java/blob/v0.9.8/client-v2/src/main/java/com/clickhouse/client/api/ClientConfigProperties.java
- https://github.com/ClickHouse/clickhouse-java/blob/v0.9.8/client-v2/src/main/java/com/clickhouse/client/api/internal/HttpAPIClientHelper.java
- https://github.com/ClickHouse/clickhouse-java/blob/v0.9.8/jdbc-v2/src/main/java/com/clickhouse/jdbc/internal/JdbcConfiguration.java

## Change

OlafStorageClickHouse supplies JDBC connection properties (milliseconds):
- socket_timeout=60000
- connection_timeout=5000
- connection_request_timeout=5000

These bound individual network waits, not recording duration or total query
runtime. Slow continuous responses and retries can exceed 60 seconds overall.
Explicit URL properties override defaults in JDBC 0.9.8, including disabling
this protection with socket_timeout=0. Deployment retains the canary URL,
which must have no conflicting overrides.

Existing query-failure cleanup remains: discard partial hits, clear query
queue, propagate failure. Matching thresholds/order/fingerprint generation
unchanged. Store connections also receive these defaults; this rollout is
limited to a Stage-1 worker, not store/refine services.

## Verification

23 tests passed (Gradle/JDK17): 4 quantization impulse, 6 query failure,
6 monitor window failure, 6 Redis task dispatcher, 1 socket timeout.
New test uses real JDBC0.9.8 against a local HTTP endpoint serving a complete
RowBinary row, then stalling mid-next-row. No socket_timeout URL override.
Failure after 60.404 seconds includes SocketTimeoutException; partial hits
and queued hashes cleared; the same execution thread successfully runs the
next query. Test uses uncompressed transport; production uses LZ4 over the
same HTTP socket. No production outage was induced.

## Artifact and canary

Base JAR: 83fdabf6150d92060d85b678430eaa8ea3f7eb1d4b9bb82584e8e878dd2a93d4
New JAR: 60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e
Only changed ZIP entry: be/panako/strategy/olaf/storage/OlafStorageClickHouse.class
Compiled with --release 11 from the validated artifact's source plus the
connection-method patch; unrelated dirty source changes excluded.
Image: local/ozen-panako:jdbc-timeout-20261002-60a75e63

Target: project-panako-api-memory-1 on167.233.61.250.
Retains ch-read-router:18123 route, 2 windows, 3584MiB heap, 4.5GiB limit.
Private deployment evidence: /home/bekzat/jdbc-timeout-20261002/.
Persistent override: /home/bekzat/project/docker-compose.jdbc-timeout-20261002.yml.
Drain before recreation; compare merged compose config and live env/resources;
verify health, JAR SHA and unrelated container IDs.

Deployment and task-completion outcome pending at report creation.
Do not expand fleet until actual canary task completion/error checks pass.

First deployment attempt hit its30min drain timeout and exited without
recreating the worker (phase `rolled_back`; old container retained).
Worker continued completing tasks and taking new ones. Retry started05:05:36UTC,
private root /home/bekzat/jdbc-timeout-20261002-retry1/.
Retry polls only the known consumer's XPENDING (read-only, count1, every50ms),
then pauses and rechecks the full consumer state before replacing the container.
No Redis acknowledgements or claims performed by deployment scripts.
Canary URL confirmed to have no timeout overrides.

At05:12:12UTC retry remained `draining`; production still used the old image.
A separate observer process (PID1549910 at05:08UTC) was launched. It waits
for successful deployment, then runs a30min watch and writes gate-result.json;
on deployment failure it exits. No automatic fleet expansion is configured.

## Canary installed

Both30min idle-drain attempts expired while the worker continued processing.
Second attempt used50ms pending polling but still did not capture an idle gap.
After the user directed continuation, a controlled single-container replacement
completed at2026-10-02 05:35:54UTC. JAR SHA, environment, resource limits,
HTTP health, and unrelated container IDs verified. New ID:
7a5fd88e4c6bfd4ed12664657580f7d219e6aea558c3eb51ad33176c65350f99.
Private evidence and30min observer: /home/bekzat/jdbc-timeout-20261002-controlled/.
The observer was actually started (PID1636711); no fleet expansion scheduled.

One interrupted request1790883270352-0 remained in Redis pending before/after
stop; recording01a0f898-52fd-7098-b32b-50f5f2bccba6. No manual ACK/claim/requeue.
Its eventual automatic reclaim/completion still requires verification; this
replacement can delay its processing. Remaining25 Stage-1 workers retain83JAR.
