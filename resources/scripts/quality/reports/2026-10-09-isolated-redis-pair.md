# Isolated Stage-1 / refine pair

User requested one Stage-1 worker and one refine consumer on a different Redis, authorized any existing host and confirmed ClickHouse Main 10.0.1.2.

## Deployment

Host: **167.233.61.228**. Compose project: `panako-isolated-pair`. Private deployment root: `/home/bekzat/isolated-pair-20261009`. Started 2026-10-09 12:47:02 UTC; both services healthy at 12:47:05 UTC / 17:47:05 Almaty.

| Role | Container | Request stream | Consumer group | Local HTTP health port |
| --- | --- | --- | --- | ---: |
| Stage-1 | panako-isolated-pair-stage1-1 | panako.monitor.request | panako-workers | 18080 |
| Refine | panako-isolated-pair-refine-1 | panako.monitor.refine.request | panako-worker-refine | 18081 |

Response streams: `panako.monitor.response` and `panako.monitor.refine.response`. Both services use **Redis 10.0.0.4:6379**, with the supplied authentication, and **ClickHouse 10.0.1.2:8123**. Kafka is disabled. Existing seven containers on this host retain their original IDs, settings and Redis 10.0.0.2. No original worker was replaced or switched to the new Redis.

Each new service: **3 CPU limit, 3584 MiB heap, 4608 MiB container memory, two windows and one Redis worker thread**. Host memory available before deployment: 19.6 GiB; after startup: 18.9 GiB. Six monitor consumers on the host now have combined memory limits of 27 GiB, plus the existing two store consumers and HTTP service. Runtime memory under a busy new queue has not yet been measured.

Both images were already present on this host; no rebuild or additional image transfer. Stage-1 uses the existing host image `sha256:7f3aad49dcfa40f8acd6a5654a72b7d0733c529b64797750126adc8440b349f5`. Refine uses `sha256:71a4f8121ba8b702bbdb2ed6ecd8b61e771b3d4e15e0300681a31d61128e3bee`. Both running JARs match validated SHA256 `60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e`. Validated direct-origin downloads and JDBC socket autotuning are configured; recognition settings remain unchanged.

## Verification

At 12:49:26 UTC:

- Both HTTP health checks pass; both Main credentials pass read-only `SELECT 1`.
- Redis authentication/PING works; server reports Redis 8.10.1.
- Each request stream has exactly one consumer in the intended group, zero pending and zero lag. No messages have been delivered yet: both queues are empty.
- `ACL DRYRUN` confirms EVAL, XPENDING, XCLAIM, XACK and response XADD permissions. These were permission simulations, not manual claims/ACKs or injected jobs.
- No restarts, OOM, Redis authentication/permission failures, read-loop errors or task failures.
- Existing services retain their IDs, running state and original Redis endpoint.

Preflight initially encountered closed non-TTY stdin and Compose's string representation of the memory limit. Both failed before deployment; corrected before launch. The first log verifier matched the JVM option `ExitOnOutOfMemoryError` as an error; corrected to match the actual `java.lang.OutOfMemoryError` exception class. Running services did not report an OOM.

Acceptance establishes readiness and isolation. It does not establish processing time or recognition quality on new-project tasks because none were present. No original-project requests were copied to the new Redis. This pair does not include an orchestrator; the project's producer/orchestrator supplies the Stage-1 and refine request streams and consumes their responses.

## Credentials and maintenance

The new Redis password was passed through stdin with terminal echo disabled, held in the deployment process environment, and injected into Docker's container environment. It was not written to a new deployment password file. The protected Compose file references `PAIR_REDIS_PASSWORD`; restart of existing containers does not require resupplying it, but Compose recreation does. Existing ClickHouse credentials remain in the protected configuration, matching the current fleet's configuration format. Do not print full Compose configurations or Docker environments in logs.

Stop only this pair if requested; do not stop the original `project` stack. Before stopping busy workers, record their PEL entries and verify subsequent application recovery. Do not manually ACK/delete request entries.

Evidence: `build/isolated-pair-20261009/status.json`, `verification.json`, `acl-verification.json`; matching files on the target under the private deployment root. No background monitoring was left running for this deployment.
