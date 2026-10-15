# Keeper HA migration preparation

Status: configurations prepared; isolated membership and ClickHouse endpoint
reload rehearsals passed on 26.3.12.3.
Production Keeper remains unchanged. These files are NOT an unattended
production migration. Do not start all new nodes together.

## Why MAIN receives a new ID

Existing production member is server.1=localhost:9234. On version 26.3.12.3,
restarting it with the same ID at a private-IP endpoint does not change the
committed membership: Keeper explicitly ignores that change. The initial
rehearse.py reproduces this limitation. Do not use its failed address-change
sequence for production.

rehearse_bridge.py passed on the exact production image digest with synthetic
data. It starts a second Keeper in the SAME network namespace as the original
so localhost:9234 still reaches the old member. It joins the new member with a
network-reachable endpoint, removes the original ID, adds remote nodes one at
a time, then verifies reads AND writes after stopping the new MAIN Keeper.
Production equivalent: two independent Keeper processes on MAIN host network,
separate directories and ports, never sharing a data directory.

Final membership:

| ID | Host | Client port | Raft port |
|---|---|---|---|
| 4 | MAIN 10.0.1.2 | 19181 | 19234 |
| 2 | replica 10.0.0.12 | 9181 | 9234 |
| 3 | replica 10.0.0.10 | 9181 | 9234 |

MAIN's new client/Raft ports intentionally differ from old Keeper's 9181/9234,
allowing both to coexist. New MAIN container is clickhouse-keeper-main-v2.
Do not rename ID 4 back to ID 1 or assume endpoint changes are permitted later.

## Gates before production execution

1. Finish SQL-failure canary and controlled rollout so a failed DB request
   cannot publish a recognition result assembled from incomplete query data.
2. Verify worker-side read routing and interrupted-task recovery before any
   ClickHouse restart. Existing production workers still use MAIN directly.
3. ClickHouse 26.3.12.3 endpoint reload has passed in isolation with a synthetic
   ReplicatedMergeTree. Apply the tested procedure separately to every live
   client and check its session and all replicated tables. Production versions
   and configuration layouts must match the assumptions. If a live client
   requires restart, drain/reroute its ClickHouse service first.
4. Audit all Keeper clients (MAIN clickhouse/clickhouse2 and remaining replica
   servers), confirm peer private-IP reachability and free ports, and capture
   current Compose identities, configuration, committed membership, sessions,
   replication state, snapshot/log indexes, and checksums.
5. Before enabling reconfiguration on old Keeper, capture a consistent backup
   of its configuration and entire /srv/clickhouse/data/keeper directory during
   the controlled Keeper stop/restart. Do not label a live tar of changing logs
   a consistent backup. Preserve its existing bind mount, anonymous volume,
   server ID, and Raft endpoint. Do not recreate a fresh production quorum.

## Prepared sequence

1. Enable reconfiguration on OLD Keeper using
   keeper-1-enable-reconfiguration.xml. Its ID 1 and localhost:9234 stay intact.
   Apply via controlled restart and confirm the existing namespace, sessions,
   and replica health recover. Do not restart ClickHouse with this operation.
2. Provision a new MAIN directory (e.g. /srv/clickhouse-keeper-main-v2) with its
   own data/log paths. Use compose-4.json, initially with the contents of
   keeper-4-bridge.xml installed as keeper-4.xml. Both processes share host
   networking. The transitional hostname check exception is limited to this
   two-member bridge configuration.
3. From OLD Keeper add only ID 4:
   reconfig add "server.4=10.0.1.2:19234;participant;1"
   Verify /keeper/config includes IDs 1 and 4, new member is synchronized, and
   known production namespace paths are readable through port 19181.
4. Update every ClickHouse client's Keeper endpoints with the verified
   reload/reconnect procedure. The optional additive transition config includes
   MAIN ports 9181 and 19181 but does not prove a move away from old Keeper.
   Install clickhouse-zookeeper-main-v2.xml (only MAIN 19181), then execute
   SYSTEM RELOAD CONFIG. Inspect system.zookeeper_connection: port must be
   19181, session not expired. Verify system.replicas has no readonly/expired
   sessions and healthy queues. Prove every client uses 19181 BEFORE removing
   ID 1. Membership changes do not migrate client connections automatically.
5. Via new MAIN port 19181: reconfig remove "1". Wait for committed membership
   to contain only ID 4 and verify existing namespace plus replication health.
   Stop old Keeper and disable its Compose/restart policy. Preserve old data.
   Install keeper-4-single.xml as new MAIN's configuration; verify safe restart
   without reintroducing old ID 1. Do not reuse the old volume for ID 4.
6. Start ID 2 on 10.0.0.12 using compose-2.json and keeper-2.xml; then join:
   reconfig add "server.2=10.0.0.12:9234;participant;1"
   Wait for committed membership and caught-up follower before continuing.
7. Start ID 3 on 10.0.0.10 using compose-3.json and keeper-3.xml; then join:
   reconfig add "server.3=10.0.0.10:9234;participant;1"
   Verify committed IDs 4,2,3 and two synchronized followers.
8. Persist keeper-4.xml final membership on MAIN. Update all ClickHouse clients
   to final clickhouse-zookeeper.xml (MAIN 19181, replicas 9181). Verify all
   sessions and queues using the previously tested reconnect procedure.
9. Test one-member failure only after quorum health and client migration pass;
   verify coordination reads/writes and replica health before restoring it.
   Measure fallback query capacity separately; this is not a 26-worker load test.

## Rollback boundaries

Before joining ID 4, restore old Keeper configuration with its original data.
After any committed membership change, inspect /keeper/config and maintain a
live majority. Remove new members one at a time through the current cluster
when rolling back membership. After ID 1 is removed, do not simply start its
old snapshot and point clients at it: that would create a divergent namespace.
Keep backups for controlled disaster recovery, not ordinary membership rollback.
Never remove Raft data to force startup. Never copy an old snapshot over a live
cluster. Leave the last healthy committed membership intact on failure.

Reference: https://clickhouse.com/docs/guides/oss/deployment-and-scaling/keeper
(dynamic reconfiguration and converting one node to a cluster).

## ClickHouse client rehearsal evidence

rehearse_clients.py passed at 08:30:54 UTC on September 29. Synthetic
ReplicatedMergeTree writes survived config reload, old member removal,
stopping the actual connected Keeper, and subsequently stopping MAIN Keeper.
ClickHouse container identity/start time remained unchanged. Result saved in
client-rehearsal-result.json; limitations and sequence are documented in
../reports/2026-09-29-keeper-client-rehearsal.md. All test containers stopped.
