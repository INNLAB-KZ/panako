# Lease-aware refine canary on 62.238.0.200

## Scope

Replace one legacy refine (`project-panako-api-7`) with the previously validated JAR `60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e`. No full project JAR rebuild. The local ACK/heartbeat warning-race patch is not included in this rollout.

Keep private ClickHouse replica `10.0.0.22`, recognition settings, two windows and one Redis worker thread. Heap changes from 3 GiB to 3584 MiB, container limit from 4 GiB to 4608 MiB; CPU quota 3. Host has 16 CPUs, about 30.6 GiB RAM and 17.3 GiB available at preflight. There remain six live refine consumers on the host: five old shared-service containers plus the replacement canary.

Private backup, source inspection, build and deployment artifacts: `/home/bekzat/refine-lease-canary-20261009`. Canary port 8094 checked free. Deployment script validates effective configuration, copies live environment, verifies JAR, container resources and health, and preserves other running containers. Idle drain is bounded at 180 seconds; any interrupted request is tracked for normal reclaim, with no manual Redis mutations.

## Status

Preparation in progress. The previous source retirement on 157.180.26.0 still has request `1790883396704-0` awaiting reclaim as of 05:30 UTC; its separate recovery observer remains active.

Deployment completed at 05:33:52 UTC / 10:33:52 Almaty. The source reached zero pending requests and was stopped idle; no task was interrupted by this replacement. New container `refine-lease-canary-20261009-refine-canary-1`, ID `4bc5e6c7a87417a961bdd73b3c1365d52be1c09c29aa8e2bc5bedf5fded038ae`. Environment, resource, JAR and HTTP checks passed; unrelated containers were unchanged. Old source remains stopped with restart=no, shared-service persistent replicas now five.

Resource observer, task/lease observer and source-recovery checker launched after successful startup. First 30-minute resource checkpoint expected around 11:05 Almaty. Full hourly SLA is not established by startup health. Eleven legacy refine consumers remain across the other five containers here and six on 167.233.62.118. Total fleet count remains 28.
