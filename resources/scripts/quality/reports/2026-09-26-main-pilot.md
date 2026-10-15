# Single-worker MAIN pilot — September 26, 2026

## Scope and preflight

After completion of the 22-worker input-fix rollout, the user authorized the
next stage. Only `project-panako-api-memory-1` on 167.233.61.250 was switched
from replica `10.0.0.9:8123` to MAIN `10.0.1.2:8123`. An authenticated probe
identified MAIN as `ch01`. MAIN's physical interface reports 10000 Mbps.

Both endpoints reported 2,308,182,381 fingerprint rows and 210,624 metadata
rows, with replication queue and absolute delay zero. Row counts are a
consistency preflight, not proof of identical table contents. Over the prior
15 minutes, MAIN query-log p95 was 137 ms, maximum 2471 ms, with no query
exceptions; replica p95 was 285 ms, maximum 613 ms. These are different
workloads, not a controlled performance comparison.

## Deployment

The worker passed startup checks at 20:15:36 UTC on September 25
(01:15:36 Almaty, September 26). Its previous task was acknowledged before
stopping. Any task admitted at the handoff remained pending for retry.
Other project container IDs were verified unchanged.

Only `CLICKHOUSE_HOST` and `OLAF_CLICKHOUSE_URL` were changed. The input-fix
image/JAR, two windows, 3.5 GiB heap, 4.5 GiB container limit, Redis routes,
audio routes, and recognition code remain unchanged. Twenty-one workers
continue using their existing replicas.

Private operational artifacts on the worker host:
`/home/bekzat/main-pilot-20260926/`. Additional Compose override:
`/home/bekzat/project/docker-compose.main-pilot-20260926.yml`.
`compose-command.json` records the full ordered Compose file list, including
the previous input-fix override. Use that list for subsequent operations.
`rollback.py` verifies container identity and override contents before restoring
the replica route for this service only.

## Observation and decision

- Pilot watcher runs for 30 minutes from 20:15:37 UTC; expected completion
  approximately 20:46 UTC / 01:46 Almaty.
- Five other workers on the same host are observed separately in `control/`.
  They process different live recordings, so cohort timing differences do not
  establish same-recording speed or recognition equivalence.
- `main-queries.jsonl` samples fingerprint query counts, p95, maximum, and
  exceptions from MAIN every 30 seconds over rolling one-minute windows.
  Query-log flush delay applies; overlapping samples must not be summed.
- Pilot/control network collectors read their respective container TCP
  namespaces. MAIN physical/VLAN byte, drop, and error counters are recorded
  separately at `/home/bekzat/main-pilot-20260926/main-network.jsonl` on MAIN.
  Physical and VLAN byte counts must not be added together.
- The existing worker timing/error gate produces `gate-result.json`. Fatal
  worker failures trigger guarded rollback. Other failed gates stop expansion
  for review. MAIN query/network measurements and control comparison require
  review before any further traffic is moved; no automatic fleet expansion
  is scheduled by this pilot.

Initial pilot samples had no alerts, restarts, or OOM. First MAIN fingerprint
samples had p95 137–139 ms, maximum 348–353 ms, and zero exceptions. These
startup measurements do not establish the hourly-recording SLA or fleet capacity.

## Result — PASS at 01:45:39 Almaty

The 30-minute worker gate passed. Seven hourly recordings averaged 245.607 s
recognition time, maximum 353.372 s. Six complete task-service upper bounds
averaged 303.167 s, maximum 387.917 s. No monitored errors, ownership loss,
OOM, restarts, or pilot TCP retransmissions occurred.

The five replica control workers completed 34 hourly recordings: mean 249.615 s,
maximum 446.807 s, with no watcher alerts. These were different recordings;
the result supports comparable observed performance, not a causal speedup.

Across 61 rolling query-log samples on MAIN, the highest sampled fingerprint
query p95 was 252 ms and maximum query duration 1315 ms; no query exceptions
or probe failures were observed. Across 127 network samples, physical-interface
peak interval rates were 108.3 Mbps inbound and 200.4 Mbps outbound. No drop or
error counters increased. These are 15-second averages, not instantaneous peaks.

Live inspection at 02:17 Almaty confirmed the pilot still running on
10.0.1.2 with zero restarts and OOMKilled=false. One worker uses MAIN and
21 still use replicas. Next proposed step: move the five control workers on
the same host to MAIN, then repeat timing and network observations before
expanding to another host. This pilot does not establish capacity for all 22.
