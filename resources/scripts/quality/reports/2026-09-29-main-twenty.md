# MAIN expansion to twenty Stage-1 workers — September 29, 2026

User authorized continuing the move to ClickHouse MAIN. Four existing Stage-1
workers on 167.233.61.228 moved from 10.0.0.23:8123 to 10.0.1.2:8123.
The previous four-worker conversion on 167.233.105.74 had insufficient tasks
for its load gate; this expansion is explicitly not based on a passed gate.

Preflight: zero pending tasks on each selected worker, no recent Java OOM or
window errors, zero restarts. MAIN fingerprint queries over five minutes:
10087 queries, p95 211.45 ms, maximum 475 ms, zero exceptions.

All four replacements passed health, environment, image/JAR, and memory checks
at 19:19:28 UTC September 28 / 00:19:28 Almaty September 29.
Only CLICKHOUSE_HOST and OLAF_CLICKHOUSE_URL changed in the merged environment.
Image remains local/ozen-panako:input-pilot-20260925-5d50209b, JAR SHA-256
5d50209bf724eec120623c22e282b68de62c8b46fabd4712eed01615808db022.
Each worker retains two windows, one Redis execution slot, 3584 MiB Java heap,
4.5 GiB container limit, and restart unless-stopped. No active task was
interrupted; pending lists at handoff and expedited retry IDs were empty.
Unrelated container identities were verified unchanged.

Persistent Compose route override:
/home/bekzat/project/docker-compose.main-twenty-20260929.yml.
Private deployment/backup/rollback/observation artifacts:
/home/bekzat/main-twenty-20260929/ on the worker host.
Use settings.json for the original Compose file order, append the route
override, and retain panako-api scale four. Guarded rollback restores the
previous route; startup failures automatically revert this service.

A 30-minute worker health/task timing and container TCP observer was launched,
with MAIN query timing and physical/VLAN network collection. Fatal worker
failures trigger guarded rollback. Other gate failures require review;
insufficient tasks must not be counted as a pass. Expected gate around
00:50 Almaty September 29. No subsequent conversion is automatic.

Stage-1 fleet now totals 26: twenty routed to MAIN and six on 167.233.52.194
still routed to replica 10.0.0.12. No ClickHouse replica was stopped in this
step. Selection of the final two retained replicas remains separate work.

## Follow-up — 01:32 Almaty September 29

Initial 30-minute gate had no alerts but insufficient samples: two hourly
recordings completed (3731.8 and 3618 seconds). Recognition times 202.532 and
334.107 seconds; mean 268.3195 seconds (4:28), maximum 5:34. Sampled full-task
upper bounds averaged 295.285 seconds and peaked at 364.890 seconds (6:05).
TCP retransmission ratio zero. Gate remains not passed for sample sufficiency.

Live check at 20:32 UTC: all four original replacement identities retained,
HTTP health 200, zero restarts, actual Java OOM, window, Redis worker, or
ownership errors. Since switch, 15 completions across all four workers
(counts 3, 5, 3, 4), aggregate logged mean 290.264 seconds (4:50), maximum
406.486 seconds (6:46). Audio durations for the additional completions were
not verified; this aggregate must not be labeled an hourly-audio average.

MAIN last five minutes: 14504 fingerprint queries, p95 217 ms, maximum 537 ms,
zero exceptions. Separate ten-second sample: CPU busy 51.3%, available RAM
89.1 GiB, physical RX 3.7 Mbps / TX 875.3 Mbps, zero new errors or drops.
No configuration change or further migration performed during this check.
