# Keeper HA live preflight, 2026-09-30

Read-only audit after Stage-1 impulse-fix rollout. No production Keeper,
ClickHouse routing, or membership changes made during this audit.

## Verified

Seven live ClickHouse endpoints all report version 26.3.12.3:
MAIN 10.0.1.2 ports 8123/8125; 10.0.0.12, .10, .22, .23, .21 port 8123.
Both default.olaf_fingerprints and default.olaf_metadata on every endpoint:
queue_size=0, absolute_delay=0, is_readonly=0, is_session_expired=0.
Each reports eight registered replicas, seven active (previously stopped
replica remains registered). No replica deletion performed.

All seven clients use singleton MAIN Keeper port 9181. MAIN clients connect
via localhost; remote clients use 10.0.1.2. Keeper mntr reports standalone,
seven alive connections, zero outstanding requests, zero followers, 1175
znodes, 22 ephemeral nodes. ruok=imok. This reconciles the seven live clients.

Keeper image ID matches rehearsed pinned digest
sha256:76fcf48c7bf75c4a07a86ecb52b451f91a2e35f0fe0e1913ef666eb90db1021e.
Host network, ID 1, Raft localhost:9234, reconfiguration not explicitly enabled.
Config /srv/clickhouse/keeper-config/keeper_config.xml; persistent data
/srv/clickhouse/data/keeper; logs /srv/clickhouse/logs. Existing anonymous
/var/lib/clickhouse volume must also be preserved. Compose
/srv/clickhouse/docker-compose.yml.

Retained replica hosts 167.233.101.144 (.12) and 167.233.107.246 (.10):
only ClickHouse running; no listener on 9181/9234/19181/19234. Available RAM
20862 and 21137 MiB; available root disk 245 and 295 GiB respectively.
Free listener ports do not prove firewall/private Raft reachability.

## Remaining production gates

- Worker-side read failover/routing and interrupted-task recovery per existing
  keeper-ha-20260929/README.md. Workers still connect directly to MAIN.
- Inspect all client config mount layouts and authorized reload mechanism.
- Verify private peer reachability for proposed Raft/client ports.
- Capture committed membership, indexes/checksums and a consistent Keeper
  backup during controlled stop; no consistent backup created by this audit.
- Execute previously rehearsed bridge ID4 migration, moving all seven clients
  to MAIN port19181 before removing ID1; then join IDs2/3 sequentially.

Replicas are synchronized now, but independent MAIN-failure operation has
not been established. Production HA should not be claimed yet.
