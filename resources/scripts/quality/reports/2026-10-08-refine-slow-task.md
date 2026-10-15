# Slow refine task: historical path analysis

Recording01a09b28-d9e6-79cf-ac2b-998350d26eff, source audio3600s,
requested segments3579s, response717428ms. Worker157.180.26.0,
refine-lease-canary-20261007; replica10.0.0.12 (167.233.101.144).
Analysis read-only; no settings, queue entries or recognition thresholds changed.

## Worker timeline

Download success16:51:08UTC, first completed database lookup16:51:20,
segments completed17:03:05, task completion log17:03:33. Application processing_ms
is not identical to download-to-publication wall time; don't conflate the two.
The177 lookup log entries in the task interval average5.471s, maximum18.97s.
The log timer wraps processQueryQueue, including hash expansion, multiple JDBC
batches, transfer, ResultSet parsing and OlafHit allocation; it is NOT one SQL
query's server execution time. Lookup durations sum968.37s because two windows
execute concurrently; union of inferred intervals~629.52s of707.50s from first
lookup start to last lookup end. No inference of additive per-phase CPU timings.

## Historical worker host samples

50 observer samples16:51–17:03:40UTC: CPU mean59.568%, max67.1%; minimum available
RAM11.59GiB. Private interface RX mean581.89Mbps,max715.25Mbps; errors/drops0.
These host totals do not exclude per-container throttling. Historical canary
cgroup throttling/GC samples were not recorded; causality cannot be reconstructed
from today's short CPU sample.

## Replica query_log, same time interval

12869 completed fingerprint SELECT queries from client10.0.0.11 (all six worker
containers share this address), average449.6ms,p95~864ms,max2783ms,errors0.
Logical result bytes106.86GB; ProfileEvents NetworkSendBytes54.99GB, about579Mbps
across760seconds. This matches worker NIC observations. Do not attribute all bytes
or query counts to the single canary task.

Aggregated query durations5786s; NetworkSendElapsedMicroseconds4731s;
OSCPUVirtualTime2003s; OSCPUWait8.07s; DiskReadElapsed157.5s. Counters overlap
and include parallel queries; they are not additive wall-time shares. Large send
time supports transfer/client-consumption backpressure as a major investigation
lead, not proof of a physically saturated1Gbit link or a network-only cause.
Replication current state: readonly0, queues0,delay0 for both tables.

## Interpretation / next test

Most worker task wall time overlaps database lookups. Data transfer/client parsing
and inter-host latency are the primary suspect path; RAM exhaustion and replica
errors are unsupported by captured evidence. No basis to claim Main overload:
this task reads the external replica. Worker→replica current RTT~25ms vs~3ms
from91.99.52.184, not a historical latency measurement.

Next controlled test should process the same recording/settings on a worker near
the database, with per-container CPU throttling/GC and server-side query metrics.
Existing near-Main workers already occupy their planned memory reserve; don't add
an extra full worker blindly. Do not lower matching thresholds or remove passes
merely to meet the timer. Fleet upgrade/placement remains staged; this diagnosis
does not establish a guaranteed sub600s completion for every recording.

Artifacts:build/refine-lease-canary-20261007/slow-task/worker.log,
samples.jsonl,db-profile.jsonl,lookup-summary.json and read-only query scripts.
