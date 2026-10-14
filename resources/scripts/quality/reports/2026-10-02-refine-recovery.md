# Refine recovery and Main CPU offload

Fresh inspection found all three previously relocated refine workers blocked:
91.99.52.184 (F),167.233.105.218 (G),46.224.159.198 (E). Each had zero
completions in the last hour,120 cancellation-wait warnings and120 ownership
loss warnings. Healthy HTTP endpoints and idle CPU had masked stalled work.
Do not confuse project-panako-store-f-1 (previously disabled migration source)
with project-panako-refine-f-1 (different service, still on Main).

Recreated the three stalled destination containers, preserving their image,
unique hostname, other env/settings and resource limits. Added JDBC URL options
socket_timeout=60000,connection_timeout=5000,connection_request_timeout=5000.
No recognition algorithm or image changes for these refine workers.
Private backup/proposed config/status at /home/bekzat/refine-recovery-20261002/
on each host. Persistent Compose override compose.jdbc-timeout.yml is included
in new container Compose labels.

F started05:32:06UTC and completed a task in157973ms. Initial health probe used
8080 incorrectly; actual SERVER_PORT argument was8086. Verified correct endpoint
and settings manually, corrected status at05:35:04UTC. Script now reads port
from container arguments. ClickHouse was not restarted.
G passed health05:33:55UTC; completed a task in188026ms, no errors/ownership loss/
restarts, health ok at05:39:19UTC.
E passed health05:34:24UTC; first completion pending at initial check.
All three retain local/ozen-panako:refine-fix-20260927-5d50209b,
3.5GiB heap,4.5GiB limit,two windows,one Redis execution worker.

## Disable old G on Main

After G replacement's completion and health/error checks, stopped Main source
project-panako-store-g-1 at05:40:36UTC. Actual role is refine. Set restart=no and
persisted deploy.replicas=0 in its existing override:
/home/bekzat/project/docker-compose.audio-origin-f9b74f6.yml.
Merged Compose comparison confirmed only this service's restart/replicas changed
(Compose adds empty resources/placement objects for a new deploy block).
Unrelated running container IDs/states unchanged. Backups and evidence:
/home/bekzat/refine-g-disable-20261002/ on Main.
No Redis messages manually modified; unfinished source work remains subject to
normal retry. Redis duplicate-response diagnosis remains deferred by the user.

Main still has seven active refine-role containers; one old refine-f logged
Java heap OOMs in the preceding2h. Further relocations/fixes remain necessary.
A single replacement task establishes recovery, not long-term throughput/SLA.

Post-stop10sec sample around05:42UTC: Main aggregate CPU busy86.9%,iowait0.0%.
Physical ens23 TX4098.0Mbps/RX86.2Mbps; VLAN TX3912.0Mbps/RX30.7Mbps
(subset, not additive). No new interface errors/drops. This is a short sample
under varying work, not a controlled before/after benchmark.

By05:42UTC E also completed a task in411165ms (6m51s), with no sampled
monitor failures/cancellation-wait/ownership-loss/socket-timeout messages.
All three destinations therefore demonstrated at least one post-recovery
completion; these recordings differ, so their timings are not an SLA comparison.
