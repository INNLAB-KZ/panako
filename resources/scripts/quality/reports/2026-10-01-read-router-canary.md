# Worker-side ClickHouse read routing canary

Host 167.233.61.250. Deployed ch-read-router on existing project_default Docker
network, no published ports. Pinned HAProxy digest
7ee2f21c5baa25ceb5d01990b78d13a71e8468f5041ac2d69304747d4631407a.
Limits 0.5 CPU / 128 MiB, restart unless-stopped. Main 10.0.1.2:8123 primary;
10.0.0.12/.10:8123 backup, allbackups enabled. /replicas_status health checks.
Connect timeout 1 s; client/server inactivity timeouts 300 s; no additional
HTTP-status or post-send replay retry policy configured.

Before rerouting workers, sampled fingerprint rows matched exactly across all
three DBs. Runtime maintenance-state probes verified Main, fallback with Main
excluded, second replica with both Main and first replica excluded, and return
to Main. This tests routing and sample parity, not real host failure, in-flight
query retry, Keeper independence, or full-fleet fallback capacity.

Private admin socket mode600 initially prevented bekzat access; ownership
corrected and uid/gid persisted in config. Config validated, router restarted
before worker migration. No production task used the route during probes.

Migrated exactly project-panako-api-memory-1 after pending task drained;
only OLAF_CLICKHOUSE_URL authority changed to ch-read-router:18123.
All other environment/image/memory settings unchanged. Running JAR SHA verified
83fdabf6150d92060d85b678430eaa8ea3f7eb1d4b9bb82584e8e878dd2a93d4.
Unrelated container IDs unchanged. HTTP health passed.
Healthy 2026-09-30 19:27:44 UTC / 2026-10-01 00:27:44 Almaty.
30-minute observation starts immediately, expected result around 00:58 Almaty.
Only 1/26 workers routed through proxy; remaining25 direct MAIN. All26 retain
impulse fix. Keeper remains singleton; no Keeper migration performed.

Private proxy artifacts: /home/bekzat/read-router-20261001/.
Private worker artifacts: /home/bekzat/read-router-canary-20261001/.
Override: /home/bekzat/project/docker-compose.read-router-20261001.yml.
Expansion waits on actual recognition tasks and production routing evidence.
