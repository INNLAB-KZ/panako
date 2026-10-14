# MAIN expansion to twelve workers — September 27, 2026

Following the successful six-worker stage and fresh health checks, the user
authorized continuation. Six `panako-api` workers on 167.233.53.95 are selected
to move from `10.0.0.21:8123` to MAIN `10.0.1.2:8123`. Ten workers on other
hosts remain on replicas. No replicas are stopped or removed.

Preflight verified the merged environment changes only CLICKHOUSE_HOST and
OLAF_CLICKHOUSE_URL. Existing image/JAR, two windows, 3.5 GiB heap, 4.5 GiB
container limit, Redis routes, and audio routes remain unchanged.

Workers finish captured pending tasks before replacement; newly admitted
handoff tasks stay pending for retry. Deployment validates health, JAR hash,
routes, and unchanged unrelated container IDs. After startup, the existing
30-minute task/error/network gate observes these six workers. Fatal failures
trigger guarded rollback of this service; other gate failures block expansion.

The six already on MAIN at 167.233.61.250 are observed separately for 65 minutes
to capture any effects of increased load. Their logs and container TCP counters
require review over the overlapping interval before the next expansion.
MAIN query timings and physical/VLAN counters are also collected. No subsequent
host switch is automatic.

Artifacts: `/home/bekzat/main-twelve-20260927/` on both worker hosts and MAIN.
New route override on 53.95:
`/home/bekzat/project/docker-compose.main-twelve-20260927.yml`.
Use the prior file order in settings.json plus this override and preserve
panako-api scale six. Rollback restores only this stage's route override.

## Startup checkpoint — 16:10 Almaty

All six replacements passed startup and verification at 11:10:14 UTC /
16:10:14 Almaty on September 27. Unrelated container identities were unchanged.
The new-host observation began at 16:10:15 and should finish approximately
16:41. Initial samples show no alerts; the existing MAIN host's concurrent
watcher also has no alerts. Twelve workers now use MAIN; ten use replicas.
The increased-load performance decision remains pending.

## Completed observation and 23:17 Almaty status

The new host passed its gate at 11:40:26 UTC: 34 hourly recordings averaged
287.352 s, maximum 490.422 s. Twenty-eight complete task-service upper bounds
averaged 317.488 s, maximum 521.944 s. No alerts; some recordings exceeded
eight minutes, none exceeded the ten-minute recognition ceiling.

The existing MAIN host's 65-minute observation completed at 12:12:22 UTC:
70 hourly recordings averaged 319.145 s, maximum 499.294 s, with no alerts.
These cohorts cover different observation intervals. Their weighted recognition
mean across 104 recordings is approximately 308.751 s, not an estimate for the
entire period since observation ended.

At 18:17 UTC, all twelve containers were running with zero restarts and
OOMKilled=false. Ten stage1 workers remain on replicas; no additional switch
was performed during this status check.
