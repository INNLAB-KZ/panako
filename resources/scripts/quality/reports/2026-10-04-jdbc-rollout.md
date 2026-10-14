# JDBC timeout rollout — 2026-10-04

User authorized continuation after successful canary gate and current status.
Same60a75e63 JAR as the canary; only connection-default bytecode differs from
previous83fdabf6. No fingerprint/matching changes in this Stage-1 rollout.
Default socket wait60000ms, connect and pool acquisition5000ms. Confirmed no
URL timeout overrides on the25 target workers.

Updated groups (UTC health timestamps):
-167.233.61.250:5,17:35:26.
-167.233.53.95:6,17:39:39.
-167.233.52.194:6,17:40:51.
-167.233.61.228:4,17:42:27.
-167.233.105.74:4,17:42:27.
Existing canary unchanged. All26 Stage-1 workers now use
local/ozen-panako:jdbc-timeout-20261002-60a75e63.
JAR SHA60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e.

All25 drained with zero pending before/after stop; no forced active-task stop
was needed. Each deployment verified exact live env, memory/CPU/restart policy,
HTTP health, JAR SHA and unrelated container identities. Two windows,3584MiB
heap,4608MiB container limits preserved. Routing preserved:25 directMain,
1 original canary through read-router. No Redis ACK/claim/requeue by scripts.

Private evidence/observation root /home/bekzat/jdbc-rollout-20261004/ on each host.
Persistent override docker-compose.jdbc-rollout-20261004.yml in each original
Compose directory. Per-host30min observers started after deployment; first
new group demonstrated one completion during initial check. Startup health
alone is not completion/SLA evidence for every worker. Full gate results pending.

Follow-up around17:46UTC: Stage-1 group lag0,pending0. First updated group had
one completion and no observer alerts. Some newly updated workers had no new
recordings yet; absence of completions in an empty queue is not a worker failure.
All25 deployment records phase=running; zero pending at stop on every host.
