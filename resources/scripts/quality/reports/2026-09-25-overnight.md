# Unattended stage1 rollout — September 25, 2026

## Authorized scope

The user requested an unattended check at 01:39 Asia/Almaty and continuation
only if results are satisfactory. The automation updates the existing stage1
workers with the tested Redis dispatch/heartbeat and memory changes. It preserves
worker counts and their existing Redis and ClickHouse routing. It does not move
traffic to MAIN or remove ClickHouse replicas.

| Order | Host | Stage1 workers | Action |
|---|---|---:|---|
| 0 | 167.233.61.250 | 6 | Evaluate the completed host observation at 01:39 |
| 1 | 167.233.53.95 | 6 | Drain, update, observe 30 minutes, evaluate |
| 2 | 167.233.52.194 | 6 | Start only after order 1 passes |
| 3 | 167.233.61.228 | 4 | Start only after order 2 passes; store/HTTP untouched |

Each host has a detached Python controller. It survives closure of the local
terminal and does not depend on the user's Mac staying awake. The next controller
requires a positive predecessor result with the exact run identifier and a recent
timestamp. Missing results, failed checks, exceptions, or a four-hour wait deadline
prevent further expansion.

Controllers exchange only operational gate results using expiring Redis keys
`ops:ozen:rollout:20260925-d556675b:<order>` (12-hour TTL). These are separate from
request/response streams; they do not enqueue recognition tasks or acknowledge them.

## Gates

- At least 30 minutes of observation and 90 health samples.
- At least two successful completions per worker and enough hourly recordings.
- No observed process failure, restart, OOM, window/query/Redis error, hidden
  parallel task, ownership change, or increasing delivery count for an observed
  task. An initial delivery count of 2 for a deployment handoff is allowed.
- Pending heartbeat idle time no greater than 90 seconds.
- For recordings 55–65 minutes long: maximum processing time 10 minutes and
  average no greater than 8 minutes. Longer recordings also have a normalized
  processing-time limit of 10 minutes per audio hour.
- The worker response's `processing_time_ms` **excludes the audio download**.
  The gate additionally checks conservative full task-service upper bounds from
  pending-state samples, including download, processing, publication, and ACK.
  A start is bounded by the preceding sample; completion by the following sample.
  Tasks already active at the first sample are excluded from this calculation.
  Hourly task-service bounds must also stay at or below 10 minutes, with mean
  at most 8 minutes. This includes sampling overhead and excludes queue waiting.
- At least 30 passive network samples; no increase in interface drop/error
  counters, no TCP retransmission ratio above 1%, and no five consecutive samples
  above 90% of a known interface link speed. Virtual-interface speed can be unknown
  or differ from provider bandwidth limits; this does not certify VPS capacity.

A failed observation stops expansion and leaves a report. The observation gate
does not automatically revert a healthy running deployment merely for slow tasks.
Deployment failures have a rollback path; no later host starts after a failed gate.

## Preparation evidence

All three target hosts passed container-count, current-image, configuration, and
routing preflight checks. Candidate image was built locally on each host without
a registry push. Every image's `/app/panako.jar` matched SHA-256:
`d556675b5076dbe09e49eeeb5da4a403725237d41021e4346322dad1f6bf66fd`.

Thirteen Python checks passed across OOM detection and rollout gates, including
slow processing, slow full task service, unfinished observations, missing results,
transient worker errors, network retransmissions, unexpected redelivery, and
permitted initial handoff retries. These are gate tests, not a replacement for
the earlier Java and real-recording checks.

## Operations and evidence

On each worker host: `/home/bekzat/overnight-20260925/`.

- `status.json`: current controller phase.
- `gate-result.json`: final decision and measurements.
- `deployment.json`: drain, deployment, container identities, and handoff journal.
- `watch.json`, `samples.jsonl`, `network.jsonl`: observation evidence.
- `agent.log`, `deploy.log`, `watch.log`: protected logs; may contain operational
  details, so do not publish them wholesale.
- `rollback.py`: guarded rollback of that host's stage1 service.

For order 0, the original observation remains in
`/home/bekzat/host-rollout-20260925/`.
For newly updated hosts, future Compose operations must include
`docker-compose.redis-dispatch-20260925.yml` after the base and monitor files,
and preserve the service replica count shown above.

MAIN has separate passive network and ClickHouse measurements at
`/home/bekzat/overnight-metrics-20260925/` for two hours. An initial 15-minute
fingerprint-query sample had p95 around 202 ms but individual queries up to 28 s.
That long tail needs investigation before claiming MAIN has sufficient capacity
for all workers. This rollout does not test that migration.

## 01:39 checkpoint: PASS

The controller evaluated order 0 at 01:39:02 Asia/Almaty and published PASS.
There were 36 successful responses, including 33 recordings lasting 55–65
minutes. Their processing mean was 250.202 seconds (4:10), p95 415.652 seconds
(6:56), and maximum 437.962 seconds (7:18).

For 29 hourly tasks with complete observable start/end bounds, mean full-service
upper bound was 274.703 seconds (4:35) and maximum 460.269 seconds (7:40).
No monitor alerts, process failures, or unexpected redeliveries were observed.
Worker-host TCP retransmission ratio was approximately 0.153%; no interface
drop/error counter increase was observed. This is not a claim of zero packet loss.

Order 1 was released to begin deployment. Later stages remain conditional;
their completion is not claimed in this checkpoint report. The cumulative result
will be recorded in `/home/bekzat/overnight-20260925/overnight-summary.json` on
167.233.61.250. Individual host evidence remains at the paths above.

## Morning result and network scope correction

The second host deployed successfully and completed 40 hourly recordings during
its observation. Processing mean was 238.448 s, maximum 380.733 s. Full-service
upper bounds averaged 258.323 s (4:18), maximum 405.740 s (6:46).
The automation stopped expansion because its host TCP retransmission ratio was
3.291%. The last two hosts were not deployed. Twelve of 22 stage1 workers were
updated; morning live inspection found all twelve running with restart count 0.

Investigation found a measurement-scope bug: `/proc/net/snmp` on the host describes
the host network namespace, while these workers use individual Docker bridge
network namespaces. That ratio cannot be attributed to their TCP connections.
Across the six updated containers on 167.233.53.95, cumulative counters since
creation showed only 35 retransmissions across about 70 million outgoing segments;
a fresh 15-second sample showed zero. This measures the workers' outgoing TCP,
not retransmissions of large responses sent by ClickHouse.

The corresponding replica, 167.233.105.218, was also sampled: private-network
transmit rate approximately 848.66 Mbps, retransmission ratio approximately
0.101%, no interface drop/error counter increase in that 15-second interval.
The short interval does not establish sustained capacity or a VPS bandwidth cap.

The corrected gate now requires TCP samples from every managed container and
rejects host-only or incomplete samples. Host counters remain separate diagnostic
data. Sixteen Python tests passed, including explicit namespace-scope regressions.

A new 30-minute observation was started on 167.233.53.95 without recreating its
workers. On PASS, the remaining hosts resume in order, with their own full
observations. Their existing Redis and ClickHouse routing remains verified by
preflight. Failed observations continue to stop expansion.

New run: `20260925-d556675b-netns`.
Active artifacts on the three remaining chain hosts are under
`/home/bekzat/rollout-resume-20260925/`; cumulative results are in
`overnight-summary.json` on 167.233.53.95. Original overnight evidence is retained.


## 15:32 Almaty follow-up: expansion stopped at 18 workers

The corrected network recheck on 167.233.53.95 passed. Host 167.233.52.194
then deployed successfully, but its observation failed at 11:26 Almaty. The final
four workers on 167.233.61.228 remain on the baseline image. Live inspection
confirmed all six updated workers on 167.233.52.194 running with zero restarts.

There were 37 responses in that observation, including one error. Worker
project-panako-api-8 could not determine the input audio duration for recording
01a0a7c3-cdf4-7dd2-8b3f-2b9b4e32f6c2. The exception was an IOException in
MonitorHandler.getAudioDuration, before recognition. This does not establish
ClickHouse overload; the input/download/decoder cause still needs investigation.

Recording 01a0a7c1-be6c-7396-9059-791fe4ba25b3 lasted 3454 seconds and
completed successfully with 26 tracks. Recognition took 582.872 seconds (9:43);
the conservative full task-service upper bound was 625.137 seconds (10:25).
That upper bound includes sampling uncertainty and does not prove the actual
full service time exceeded ten minutes. Normalized recognition time per audio
hour exceeded ten minutes. Container TCP retransmission ratio was approximately
0.0000783%; no OOM, heartbeat, ownership, or query-window errors were recorded.
Expansion remains stopped pending investigation; MAIN migration has not begun.


## Input investigation and local fixes

The failed recording was downloaded again through the configured object-storage
origin. The response contained 63,996,602 bytes, SHA-256
4ca0dfb7545f49c3cc5906d6fbb291bb989877a6bd7fa3580ac5ea3fb71099f0.
Download took 29.43 s. An isolated ffprobe using the production candidate image
reported a valid MP4/M4A container, duration 3593.984 s, and exit code 0. This
does not reproduce the original failure or prove that the original download was
complete. Direct CDN attempts returned HTTP 403 and a TLS EOF error; those
attempts were not equivalent to the configured origin route.

A concrete parser defect was reproduced: filename:null consumed the next field
name (segments), discarding the filename extension. The local parser now accepts
a string only when the next non-whitespace character after the colon is a quote;
null/non-string values trigger the existing URL-filename fallback. The downloader
now rejects empty bodies and mismatches with advertised Content-Length, allowing
its existing IOException retry mechanism to handle incomplete responses.

Six focused Java regression tests passed. These changes are local and have not
been deployed. They do not establish the cause of the historical duration error.
The slow task's database/CPU/download breakdown still requires measurement.
