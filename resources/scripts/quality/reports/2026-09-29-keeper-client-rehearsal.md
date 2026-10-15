# Keeper migration: ClickHouse client rehearsal

PASS at 08:30:54 UTC / 13:30:54 Almaty, September 29, 2026.
Production Keeper and ClickHouse configuration were not changed.

Isolated Docker network on 167.233.105.74, no published ports or production
Keeper endpoints. Synthetic ReplicatedMergeTree and marker znodes only.
ClickHouse 26.3.12.3; Keeper pinned to production digest. One ClickHouse
container (1 CPU, 2 GiB), up to four small Keeper containers during transition.

Verified sequence:

1. Create table and insert through old singleton ID 1, localhost Raft endpoint.
2. Enable old Keeper reconfiguration, restart it, join bridge ID 4.
3. Replace ClickHouse zookeeper config with ONLY the bridge client endpoint,
   execute SYSTEM RELOAD CONFIG. system.zookeeper_connection moves from old
   port 9181 to 19181 before old Keeper is removed. Insert/read succeeds.
4. Remove old ID 1, stop it, restart bridge with standalone committed membership.
   Insert/read succeeds, table not readonly, session not expired, queue zero.
5. Join IDs 2 and 3 sequentially, persist final membership, restart bridge.
6. Reload ClickHouse with all three final Keeper endpoints. Insert succeeds.
7. Observe connected Keeper ID 3, stop exactly that process. ClickHouse connects
   to ID 4 and insert/read succeeds. Restore ID 3 and verify namespace readable.
8. Stop MAIN Keeper ID 4. ClickHouse connects to ID 2; insert/read succeeds.
   Direct Keeper marker read/write also succeeds through surviving members.
9. ClickHouse container ID and StartedAt remain unchanged throughout.

All recorded write probes succeeded on first attempt. Post-stop write/read
probe durations were 0.463 and 0.450 seconds; these exclude docker stop time
and MUST NOT be presented as full outage/reconnection duration. No continuous
in-flight workload was measured. Test uses one table replica, so it verifies
coordination/session behavior and writes, not inter-server part replication,
26-worker fallback capacity, network partition recovery, or recognition quality.

All test containers stopped afterward. Earlier first run also passed config
reload and MAIN Keeper stop, but had already selected a remote endpoint before
MAIN stop. Second run above additionally stops the actual connected endpoint.

Artifacts: ../keeper-ha-20260929/rehearse_clients.py and
../keeper-ha-20260929/client-rehearsal-result.json. Production procedure updated
with clickhouse-zookeeper-main-v2.xml so migration proves each client's move
before removing ID 1. An additive old+new endpoint list alone is insufficient.

Remaining live gates: controlled SQL-failure rollout beyond the one passed
canary; inventory/reachability checks for every Keeper client; consistent
backup during controlled old-Keeper stop; then sequential production migration.
