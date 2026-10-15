# Replica selection and isolated read failover — September 29, 2026

## Selected retained replicas

- 167.233.101.144 / 10.0.0.12, ch05, hostname debian-ch-replica-3.
- 167.233.107.246 / 10.0.0.10, ch06, hostname debian-ch-replica-4.

Both have 12 logical CPUs and approximately 23 GiB RAM, run only ClickHouse,
and have about 20.8 GiB available memory at preflight. Both already serve old
refine workloads; do not assume spare capacity sufficient for all 26 Stage-1
workers during an outage. Physical failure-domain separation was not verified.

All five remaining replica services were healthy at inspection: current
is_readonly=0, is_session_expired=0, queue_size=0, absolute_delay=0. Stored
last_queue_update_exception strings record earlier session/read-only errors;
these were not treated as evidence of a current incident.

Fingerprint rows: 2309632588; active metadata rows: 210743. MAIN and the two
selected replicas had identical active part names/checksums across both tables,
and an identical deterministic 1000-row lookup sample. These are snapshot
checks, not a guarantee of zero lag for future asynchronous replication.

## Existing clients that must be accounted for

Live container routing and recent server query_log agree:
- 157.180.26.0 / 10.0.0.11: six refine workers use retained 10.0.0.12.
- 167.233.62.118 / 10.0.0.20: six refine workers use retained 10.0.0.10.
- 62.238.0.200 / 10.0.0.6: six refine workers still use 10.0.0.22
  (46.224.159.198), which is a future conversion candidate.

Other conversion candidates: 91.99.52.184 and 167.233.105.218. They already
host one relocated refine each using MAIN; the same applies to 46.224.159.198.
No fingerprint-query clients were seen on .23/.21 during the sampled hour;
this alone does not prove absence of write/metadata clients.
No additional replica service was stopped in this step.

## Keeper and endpoint availability

The selected replica configuration points to Keeper at 10.0.1.2:9181.
MAIN's actual keeper_config.xml has one Raft member, server_id=1,
hostname=localhost, port=9234. Keeper currently shares MAIN's failure domain.
A redundant read proxy alone therefore does not provide whole-system HA.
Keeper quorum expansion and a client endpoint independent of MAIN are required
before claiming full host-failure tolerance. Existing production HAProxy also
runs on MAIN; workers currently connect directly to MAIN port 8123.

## Test design

An isolated Docker network on 167.233.105.74 contains a temporary HAProxy 3.0
container and a one-CPU, 512 MiB Java probe container using the exact deployed
worker image/JDBC driver. No listener is published on host/public ports.
Pinned proxy image digest:
sha256:7ee2f21c5baa25ceb5d01990b78d13a71e8468f5041ac2d69304747d4631407a.
MAIN is primary; both replicas are backups with option allbackups. Health checks
use /replicas_status every second, fall/rise two checks. Test runtime socket
is under a host directory mode 0700; socket access corrected to 0666 within
that private directory so the owning operator can control the test instance.

Simulated outages change only test-proxy destination addresses to a refused
loopback port. Production servers, routes, Redis queues, and Keeper remain
untouched. The Java probe reads a bounded deterministic fingerprint sample
and records serving host and digest in the SAME query, avoiding misleading
round-robin attribution from a separate hostname query.

Phases: primary healthy; primary unreachable; primary plus one replica
unreachable; all unreachable; replicas recovered; primary recovered. Stable
phase assertions exclude the initial 3.5 seconds of health-check convergence.
Full-outage errors are required; identical successful result digests required.
Containers stop after the test. This validates bounded JDBC read routing,
not recovery of an interrupted audio task, write availability, or full-load
capacity. Actual production SQL exception propagation still needs review:
OlafStorageClickHouse.processQueryQueue logs SQLException and returns, which
can allow incomplete lookup results if a request fails mid-task.

References: https://docs.haproxy.org/3.0/configuration.html (backup/allbackups),
https://clickhouse.com/docs/reference/engines/table-engines/mergetree-family/replication.
Private artifacts: /home/bekzat/failover-test-20260929/ on 167.233.105.74.

## Result — passed at 11:05:26 Almaty

97 probes executed. All successful 1000-row result sets had identical digests.
After health-check convergence, the expected paths were confirmed:
- Healthy: ch01 only, ten successful stable-phase probes.
- MAIN unavailable: both retained replicas, eleven successes.
- MAIN plus replica A unavailable: replica B, eleven successes.
- All unavailable: zero successes and eleven explicit failures in stable phase.
- Replicas restored: both replicas, ten successes.
- MAIN restored: ch01 only, ten successes.

No failed probes in the healthy/recovered stable portions of these phases.
17 errors total include deliberate total outage and transitions; do not claim
zero-error seamless failover. Final temporary worker and proxy were stopped.
Initial test attempts exposed socket permissions and a probe attribution bug;
these test-harness issues were corrected before the successful run. Prior
attempt artifacts retained. No production routes or replica services changed.

Next deployment work: ensure SQL failures invalidate recognition tasks rather
than allowing partial success; plan a three-member Keeper quorum across MAIN
and the two retained hosts; provision worker-side routing so MAIN host loss
does not also remove the proxy endpoint. Test interrupted-task recovery and
fallback capacity before migrating production routes. Before repurposing
46.224.159.198, migrate its six refine clients on 62.238.0.200 and audit
remaining write/metadata clients. No replica data/metadata deletion authorized
or performed as part of this read-failover test.
