# Refine wave 1 — 157.180.26.0

Deployment started 2026-10-08 12:08:30 UTC. Five legacy refine containers are being replaced; the existing canary remains running.

## Configuration

- Validated JAR: `60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e`.
- Image: `local/ozen-panako:refine-lease-20261007-60a75e63`.
- Two windows, one Redis worker thread, heap 3584 MiB, container 4608 MiB, CPU quota 3.
- Replica endpoint remains private `10.0.0.12`; runtime recognition settings preserved.
- Five shared Compose replicas plus one separate canary. Aggregate configured memory limit 27 GiB on a roughly 30.6 GiB host.

## Procedure and evidence

Remote private directory `/home/bekzat/refine-lease-wave1-20261008` contains original Compose, effective configuration, container inspection, deployment status and logs. Live environments of all five legacy containers matched. Effective Compose comparison permits only image, environment, memory and CPU changes.

Drain is bounded at 180 seconds. Consumers with no pending tasks are paused and checked again before stopping. Remaining consumers get 30 seconds to stop. Pending request IDs are captured for read-only recovery verification; no manual Redis ACK, claim, requeue or delete is performed.

Deployment verifies all five environments, resource limits, JAR hashes and HTTP health, and checks the canary remains running. A background observer runs at least 30 minutes, up to 40 minutes for interrupted-task recovery. It records errors, restarts, OOM, memory, completion times and successful responses for interrupted tasks. Its `passed` flag also requires a completion from every new container.

## Status

Deployment completed at 12:11:40 UTC (17:11:40 Almaty). All five new containers passed configuration, resource, JAR and HTTP health checks; the existing canary remained running. One source drained idle; four pending tasks were interrupted and are tracked for normal reclaim and successful responses. No manual Redis mutations were used.

Background observer PID 442959 started. Monitoring results are pending; expected 30-minute checkpoint 17:42 Almaty, maximum recovery observation about 17:52. Far-host SLA remains unproven: the earlier canary had some jobs over ten minutes. The separate near-replica replay completed the selected hourly recording in 469675 ms with identical returned matches.

## Follow-up at 13:36 UTC / 18:36 Almaty

The observer finished at 12:41:53 UTC with `passed=true`. All four interrupted tasks received successful responses and left the pending list. Twenty completions during the observation averaged 363713 ms (6:04), maximum 718178 ms (11:58), one above ten minutes. These are mixed refine jobs, not a duration-filtered hourly benchmark. Minimum available RAM was 13.42 GiB.

All five containers still run with zero restarts and no OOM. A bounded 50000-line-per-container scan since observer completion found 36 more completion entries, maximum 773456 ms (12:53), and one `Redis task ownership lost` warning on `project-panako-api-5`. Other selected error counters were zero. Thus the clean 30-minute observation does not establish fleet-wide ownership safety or the ten-minute SLA. The later ownership warning requires tracing; its cause is not yet established.
