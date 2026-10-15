# First remaining remote refine relocation pilot

Started 2026-10-07 06:37:05 UTC (11:37 Almaty).

Source: 62.238.0.200 / project-panako-api-7, still running against 10.0.0.22.
Destination: 91.99.52.184 / refine-r1-move-20261007-refine-from-main-1,
container f1e9bbdf1beaa64efa018eda4ecc349b0fc6aba0807b6179bac25b5651eadfaa.
Destination targets Main 10.0.1.2:8123; port8093; CPU quota3;
heap3584MiB, memory4608MiB, two windows, one Redis worker thread.

Image local/ozen-panako:refine-jdbc-20261004-60a75e63; verified JAR
60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e.
Source uses older image; this pilot includes previously validated JDBC/concurrency
fixes. Environment copied from source; changed only CLICKHOUSE_HOST,
OLAF_CLICKHOUSE_URL authority, JAVA_TOOL_OPTIONS and KAFKA_ENABLED.
Recognition parameters, including disabled additional gap pass on this particular
source, preserved. Source has no mounts. Destination uses host networking and a
unique consumer hostname. All existing destination container IDs unchanged.

Destination now has four refine containers, configured memory limits total18GiB
against22.9GiB host RAM. Pre-start available14GiB. A fifth such container would
leave insufficient configured host reserve; do not keep adding here blindly.

Health passed. Started detached read-only30-minute resource/error observer and
40-minute task/response watcher, PIDs1635541/1635542. Files and private configs:
91.99.52.184:/home/bekzat/refine-r1-move-20261007/.
No automatic source retirement installed. Acceptance requires two successful
3300–3900s audio/full-segment responses correlated to this consumer, each<=600s,
no operational alerts/restarts and >=4GiB available RAM. Not yet accepted.
Temporary live count29 refine (28 existing plus pilot); Stage-1 count26 unchanged.
No manual Redis queue mutations. ch07 remains online for six source consumers.

## Store follow-up

After all three stale registrations were removed, newly started DELETE queries
completed successfully: 06:26:23→06:30:58 (274944ms) and
06:28:41→06:33:39 (298231ms), exception_code0. Metadata deletes82–117ms.
The final query started06:23:34 failed at06:28:18 during ch08 retirement; this
precedes completion of all registration cleanup at06:25:15. Do not claim zero
transient errors throughout maintenance. New success demonstrates inactive-replica
wait resolved; multi-minute fingerprint deletion remains a separate bottleneck.

## 09:01 UTC follow-up: performance passes, migration held

Initial 30-minute observer passed: six full-hour workload responses, all ok,
processing209.544–329.236 seconds, mean286.406 seconds; minimum available RAM8.11GiB.
No OOM or restarts, current health good. Task watcher finished07:17 UTC;
its response dataset is historical, not a current continuously running observer.

Later logs contain ownership-loss warnings for two distinct Redis message IDs:
1790880386456-0 (07:47–07:51) and1790880393467-0 (08:13–08:17).
Both ended with `Redis task not acknowledged: ownership lost`.
The current check.py reports only8 warning matches because its30000-line tail
truncates earlier events; its17 completions are also a bounded-log count.
Cause of ownership change not yet proven. Source remains running; no replica or
source retirement performed during this check. Do not accept the migration based
only on the earlier clean observer. No Redis mutation performed.

## Ownership-loss investigation

Read-only request lookup maps message1790880386456-0 to recording
01a0da08-8611-7266-a30a-e1e0226dd0f9; message1790880393467-0 maps to
01a0a1f9-1e03-794c-84b2-d3dad9bea687. Neither remains pending.
A bounded read of the latest10000 refine responses found two status=ok responses
for each recording:

| Recording suffix | Earlier result | R1 result |
| --- | --- | --- |
| e1e0226dd0f9 | 686815ms | 307782ms |
| d3dad9bea687 | 638748ms | 292029ms |

R1 result timestamps match its completion interval and ownership-loss warnings.
The difference between approximate processing starts is about10minutes, consistent
with reclaim of a task still being processed without lease renewal. Source worker
identity and the exact ACK actor remain unproven at this checkpoint. The current
Java dispatcher returns ownership lost for either owner mismatch OR missing PEL
entry; this warning alone does not distinguish a stolen task from another worker's
ACK. No blanket claim of data loss or changed recognition quality follows from it.

Historical log scan script and per-host selected output saved under
build/refine-r1-move-20261007/diagnosis/. No deployments or Redis mutations during
this investigation. Mixed legacy/fixed consumers remain a candidate cause.

Historical scans completed on all six queried hosts. Earlier execution identities
confirmed by recording-specific log lines on157.180.26.0: project-panako-api-5
(first recording,07:47:25) and project-panako-api-6 (second,08:13:23).
Both run legacy image sha256:83b837e9bd0eb0546e413a220294834eb4ecbe4bfdd82d2b6a1cbc63892993b6,
with REDIS_CLAIM_IDLE_MS unset. Thus duplicate concurrent processing spans legacy
and fixed worker versions. Exact Redis ACK actor was not captured historically;
reclaim/legacy ACK explanation remains an inference supported by timing and
mixed versions. Next remediation: staged legacy refine upgrade with lease renewal
and ownership-checked ACK, retaining recognition settings and existing CH routes
until separately performance-validated. No changes applied in this diagnostic turn.
