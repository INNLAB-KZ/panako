# 2026-10-14 worker role reassignment

User requested one more refine for Redis 10.0.0.4 and conversion of six of the 26 Main Stage-1 workers to Main refine. Intended final monitor counts: Redis 10.0.0.2 has 20 Stage-1 and 29 refine; Redis 10.0.0.4 has one Stage-1 and two refine. Total remains 52.

## Placement

- 46.224.159.198: four Stage-1 become four Main refine.
- 91.99.52.184: one Stage-1 becomes Main refine; refine-a is replaced by another-project refine. Existing two unrelated refine retain their identities and routing.
- 157.180.26.0: one Stage-1 becomes Main refine. Existing five refine retain their identities and routing.
- Suspect VPS 167.233.53.95 remains isolated.

The replacement image is pinned by local content ID. JAR SHA-256 remains `7a24e3f7e2d1be928211c1b335a09c16a39d58d5e05f61b7cb76cdafb839298b`. Main workers clone the local Main refine template. The new isolated worker clones the existing isolated refine environment and limits, with separately verified runtime file hashes. Settings differing between projects, including MONITOR_GAP_MIN_SECONDS, are preserved. New container hostnames produce separate Redis consumer identities. Health binds inside the Compose network; no host port is published.

## Deployment

`deploy.py HOST prepare` checks routing, Redis reachability, application/runtime hashes, memory reservation, unchanged Compose chains and exact rendered environment. Private configuration and backups remain under `/home/bekzat/rebalance-20261014`, directory 0700/files 0600.

`deploy.py HOST apply` waits for each selected Stage-1 to have no pending task, pauses it, waits beyond the blocking Redis read, then rechecks pending ownership. If a request arrived, it resumes and waits. Only a frozen idle consumer is intentionally killed, with restart disabled. Existing refine uses the previously verified cooperative drain agent before a graceful stop. No direct Redis XADD/XACK/XCLAIM/XDEL or group cursor changes occur.

After all selected consumers stop safely, their last Compose overrides are persisted at replicas=0. New services start in `panako-refine-rebalance-20261014`; health, environment, JAR hash, limits and unchanged unrelated containers are checked. Real-task acceptance follows separately. Do not rerun an attempted apply blindly; inspect status and individual retirement receipts first.

## Rollback

1. Inspect `status.json`, individual `*-retired.json`, and live containers. Never stop an active replacement to restore its predecessor.
2. For each replacement, use its own Redis host/group and hostname to wait for pending=0, freeze, wait longer than REDIS_BLOCK_MS, and recheck. Resume if it acquired a task. Stop only confirmed idle replacements with restart disabled.
3. Persist replacement Compose replicas=0. Restore the exact old overrides from `before-private.json` only for slots actually freed.
4. Restore the retired original container restart policy from its saved inspection and start that retained container. JVM restart clears the old cooperative-drain flag. Do not use Compose up on an entire old project unless all its slots have been freed.
5. Verify original environment, group membership, health, counts and memory reservations. Leave unrelated services untouched.

If drain fails before replacements start, inspect per-container receipts: already retired sources may be safely restored with their original policy, while still-running sources must be resumed if cooperatively drained. A failure after replacement start requires checking pending ownership first. Backups contain credentials and must not be committed.
