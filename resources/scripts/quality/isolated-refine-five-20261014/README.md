# Expand isolated refine to five workers — 2026-10-14

User clarified the separate project must have five refine workers, so three Main refine slots on 46.224.159.198 are reassigned. Final fleet: Main Redis 10.0.0.2 has 20 Stage-1 + 26 refine; separate Redis 10.0.0.4 has one Stage-1 + five refine. Total remains 52.

## Deployment

`deploy.py prepare` validates exact deployed runtime hashes, existing isolated project configuration, Redis connectivity and memory headroom. Local image is pinned by content ID; application JAR remains `7a24e3f7e2d1be928211c1b335a09c16a39d58d5e05f61b7cb76cdafb839298b`. New workers use three CPUs, 3584 MiB heap, 4608 MiB container memory, two windows and Main 10.0.1.2. Existing isolated project's recognition settings are preserved.

`deploy.py apply` cooperatively drains Main refine instances 2, 3 and 4 from `panako-refine-rebalance-20261014`, waits for pending=0 and stops them gracefully. Instance 1 retains its ID, settings and Main Redis routing. Old Compose replica count becomes one; new isolated project `panako-isolated-refine-five-20261014` has three replicas. Host memory reservation remains 18 GiB; CPU quotas total 11.5 of 12 CPUs. No direct Redis stream or consumer-group mutations occur.

Private backups and receipts: `/home/bekzat/isolated-refine-five-20261014` (0700, files 0600), copied under local `build/isolated-refine-five-20261014`. Credentials remain out of tracked scripts. Bounded observer requires a successful real task on each new worker, no monitored errors, restarts or OOM, then exits.

## Rollback

Wait for each isolated replacement's Redis 10.0.0.4 pending count to become zero, freeze it, wait beyond the blocking read and recheck. Resume if it acquired a task. Stop only confirmed idle replacements and persist new Compose replicas=0. Restore the exact previous Main Compose file from `before-private.json` only after all three slots are freed, restore each retained Main container's restart policy, then start instances 2–4. The surviving Main instance 1 stays running. Verify health, Redis identity, counts and memory reservations. Never start old workers while replacement slots are occupied.
